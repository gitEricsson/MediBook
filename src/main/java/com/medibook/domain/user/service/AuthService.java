package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.dto.*;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.infrastructure.metrics.TokenMetrics;
import com.medibook.messaging.event.AuditEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import com.medibook.security.JwtTokenProvider;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository           userRepository;
    private final PasswordEncoder          passwordEncoder;
    private final AuthenticationManager    authenticationManager;
    private final JwtTokenProvider         tokenProvider;
    private final RefreshTokenService      refreshTokenService;
    private final EmailOtpService          emailOtpService;
    private final PasswordResetService     passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final TokenMetrics             tokenMetrics;
    private final SessionTimeoutService    sessionTimeoutService;
    private final AppointmentEventProducer eventProducer;
    private final DoctorRepository         doctorRepository;


    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new MediBookException("Email already registered", HttpStatus.CONFLICT, "EMAIL_TAKEN");
        }

        User saved = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .dateOfBirth(request.getDob())
                .role(Role.ROLE_PATIENT)
                .enabled(true)
                .isActive(true)   // auto-activate self-registered patients; email verification stays as an optional follow-up
                .build());

        log.info("New patient registered (auto-activated): id={} email={}", saved.getId(), saved.getEmail());

        // Emit audit event
        AuditEvent auditEvent = AuditEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .action("REGISTER")
                .actorId(saved.getId())
                .actorEmail(saved.getEmail())
                .resourceType("User")
                .resourceId(String.valueOf(saved.getId()))
                .detail("Patient registered: " + saved.getFullName())
                .occurredAt(LocalDateTime.now())
                .build();
        eventProducer.publishAuditEvent(auditEvent);

        // Fire-and-forget verification email — non-blocking. Failure must not break registration.
        try {
            String verifyToken = emailVerificationService.createToken(saved.getId());
            emailVerificationService.sendVerificationEmail(saved.getEmail(), verifyToken);
        } catch (Exception ex) {
            log.warn("Verification email send failed for {}: {}", saved.getEmail(), ex.getMessage());
        }

        // Return a profile-only response; the client must call /login explicitly to obtain tokens.
        return TokenResponse.builder()
                .user(UserResponse.fromUser(saved))
                .build();
    }


    @Transactional
    public TokenResponse login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.getEmail());
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, request.getPassword()));
        } catch (AuthenticationException ex) {
            log.warn("Failed login attempt for: {} — {}", normalizedEmail, ex.getClass().getSimpleName());

            // Emit LOGIN_FAILURE audit event
            User attemptedUser = userRepository.findByEmail(normalizedEmail).orElse(null);
            if (attemptedUser != null) {
                AuditEvent auditEvent = AuditEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .action("LOGIN_FAILURE")
                        .actorId(attemptedUser.getId())
                        .actorEmail(attemptedUser.getEmail())
                        .resourceType("User")
                        .resourceId(String.valueOf(attemptedUser.getId()))
                        .detail("Failed login attempt: " + ex.getClass().getSimpleName())
                        .occurredAt(LocalDateTime.now())
                        .build();
                eventProducer.publishAuditEvent(auditEvent);
            }
            throw ex;
        }

        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        ensureCanAuthenticate(principal.isAccountEnabled(), principal.getId());

        // Check email verification (skip for test users)
        if (!principal.isActive() && !isTestUser(principal.getEmail())) {
            throw new MediBookException(
                    "Email not verified. Check your inbox or request a new verification link.",
                    HttpStatus.FORBIDDEN,
                    "EMAIL_NOT_VERIFIED");
        }

        if (principal.isTwoFactorEnabled()) {
            String otp = emailOtpService.generateAndStore(principal.getEmail());
            emailOtpService.sendOtpEmail(principal.getEmail(), otp);
            return TokenResponse.twoFactorChallenge();
        }

        return issueTokenPair(auth);
    }


    @Transactional
    public TokenResponse verifyTwoFactor(TwoFactorVerifyRequest request) {
        String email = normalizeEmail(request.getEmail());
        emailOtpService.verify(email, request.getOtp());

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
        ensureCanAuthenticate(user.isEnabled() && user.isActive(), user.getId());

        return buildTokenResponse(user);
    }


    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        var rotation = refreshTokenService.rotate(request.getRefreshToken());

        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", rotation.userId()));
        ensureCanAuthenticate(user.isEnabled() && user.isActive(), user.getId());

        // Record token rotation metric
        tokenMetrics.recordTokenRotation(rotation.userId());

        String accessToken = tokenProvider.generateAccessTokenFromUserId(
                rotation.userId(), user.getEmail(), user.getRole().name());

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rotation.newToken())
                .tokenType("Bearer")
                .expiresIn(tokenProvider.getAccessTokenExpirationMs() / 1000)
                .build();
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
        // Metric is recorded within RefreshTokenService.revoke() through injected TokenMetrics
    }


    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(normalizeEmail(request.getEmail())).ifPresent(user -> {
            String token = passwordResetService.createToken(user.getId());
            passwordResetService.sendResetEmail(user.getEmail(), token);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Long userId = passwordResetService.validateAndConsume(request.getToken());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Verify email before allowing password reset
        if (!user.isActive()) {
            throw new MediBookException(
                    "Email not verified. Check your inbox or request a new verification link.",
                    HttpStatus.FORBIDDEN,
                    "EMAIL_NOT_VERIFIED");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(userId);
        log.info("Password reset completed for userId={}", userId);

        // Emit PASSWORD_RESET audit event
        AuditEvent auditEvent = AuditEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .action("PASSWORD_RESET")
                .actorId(userId)
                .actorEmail(user.getEmail())
                .resourceType("User")
                .resourceId(String.valueOf(userId))
                .detail("Password reset completed for user: " + user.getEmail())
                .occurredAt(LocalDateTime.now())
                .build();
        eventProducer.publishAuditEvent(auditEvent);
    }


    @Transactional
    public void verifyEmail(EmailVerifyRequest request) {
        Long userId = emailVerificationService.validateAndConsume(request.getToken());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setActive(true);
        userRepository.save(user);
        log.info("Email verified for userId={}", userId);
    }

    @Transactional
    public void resendVerificationEmail(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.isActive()) {
            throw new MediBookException("Email is already verified", HttpStatus.BAD_REQUEST, "ALREADY_VERIFIED");
        }
        String token = emailVerificationService.createToken(userId);
        emailVerificationService.sendVerificationEmail(user.getEmail(), token);
    }

    @Transactional
    public void resendVerificationEmail(ResendVerificationRequest request) {
        userRepository.findByEmail(normalizeEmail(request.getEmail())).ifPresent(user -> {
            if (user.isActive()) {
                return;
            }
            String token = emailVerificationService.createToken(user.getId());
            emailVerificationService.sendVerificationEmail(user.getEmail(), token);
        });
    }


    @Transactional
    public void enableTwoFactor(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setTwoFactorEnabled(true);
        userRepository.save(user);
        log.info("2FA enabled for userId={}", userId);
    }


    /**
     * Issues a token pair from a fully-authenticated principal.
     * The principal was loaded during auth — no extra DB query needed.
     */
    private TokenResponse issueTokenPair(Authentication auth) {
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        String accessToken  = tokenProvider.generateAccessToken(auth);
        String refreshToken = refreshTokenService.createRefreshToken(principal.getId());

        // Initialize lastActivityAt on successful login
        User user = userRepository.findById(principal.getId()).orElse(null);
        if (user != null) {
            sessionTimeoutService.updateActivity(principal.getId());

            // Emit LOGIN_SUCCESS audit event
            AuditEvent auditEvent = AuditEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .action("LOGIN_SUCCESS")
                    .actorId(principal.getId())
                    .actorEmail(principal.getEmail())
                    .resourceType("User")
                    .resourceId(String.valueOf(principal.getId()))
                    .detail("Successful login: " + principal.getEmail())
                    .occurredAt(LocalDateTime.now())
                    .build();
            eventProducer.publishAuditEvent(auditEvent);
        }

        UserResponse userResponse = principal.toUserResponse();
        enrichDoctorProfileId(userResponse, principal.getId());

        return TokenResponse.builder()
                .user(userResponse)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(tokenProvider.getAccessTokenExpirationMs() / 1000)
                .build();
    }

    /**
     * Issues a token pair from a User entity (register, 2FA verify paths).
     */
    private TokenResponse buildTokenResponse(User user) {
        ensureCanAuthenticate(user.isEnabled() && user.isActive(), user.getId());
        String accessToken  = tokenProvider.generateAccessTokenFromUserId(
                user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = refreshTokenService.createRefreshToken(user.getId());

        // Initialize lastActivityAt on successful login/2FA verification
        sessionTimeoutService.updateActivity(user.getId());

        UserResponse userResponse = UserResponse.fromUser(user);
        enrichDoctorProfileId(userResponse, user.getId());

        return TokenResponse.builder()
                .user(userResponse)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(tokenProvider.getAccessTokenExpirationMs() / 1000)
                .build();
    }

    private void enrichDoctorProfileId(UserResponse response, Long userId) {
        if (response.getRole() == Role.ROLE_DOCTOR) {
            doctorRepository.findByUserId(userId)
                    .ifPresent(doctor -> response.setDoctorProfileId(doctor.getId()));
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private boolean isTestUser(String email) {
        return email.endsWith("@test.com")
            || email.equals("patient@test.com")
            || email.equals("doctor@test.com")
            || email.equals("admin@test.com");
    }

    private void ensureCanAuthenticate(boolean allowed, Long userId) {
        if (!allowed) {
            throw new MediBookException("Account is not active", HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE");
        }
    }
}
