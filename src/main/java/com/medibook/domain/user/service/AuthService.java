package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.user.dto.*;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
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


    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new MediBookException("Email already registered", HttpStatus.CONFLICT, "EMAIL_TAKEN");
        }

        User saved = userRepository.save(User.builder()
                .email(request.getEmail().toLowerCase())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .dateOfBirth(request.getDob())
                .role(Role.ROLE_PATIENT)
                .build());

        log.info("New patient registered: id={} email={}", saved.getId(), saved.getEmail());

        String verifyToken = emailVerificationService.createToken(saved.getId());
        emailVerificationService.sendVerificationEmail(saved.getEmail(), verifyToken);

        return buildTokenResponse(saved);
    }


    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (AuthenticationException ex) {
            log.warn("Failed login attempt for: {} — {}", request.getEmail(), ex.getClass().getSimpleName());
            throw ex;
        }

        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        if (principal.isTwoFactorEnabled()) {
            String otp = emailOtpService.generateAndStore(principal.getEmail());
            emailOtpService.sendOtpEmail(principal.getEmail(), otp);
            return TokenResponse.twoFactorChallenge();
        }

        return issueTokenPair(auth);
    }


    @Transactional(readOnly = true)
    public TokenResponse verifyTwoFactor(TwoFactorVerifyRequest request) {
        emailOtpService.verify(request.getEmail(), request.getOtp());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", request.getEmail()));

        return buildTokenResponse(user);
    }


    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshTokenRequest request) {
        var rotation = refreshTokenService.rotate(request.getRefreshToken());

        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", rotation.userId()));

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
    }


    @Transactional(readOnly = true)
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
            String token = passwordResetService.createToken(user.getId());
            passwordResetService.sendResetEmail(user.getEmail(), token);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Long userId = passwordResetService.validateAndConsume(request.getToken());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password reset completed for userId={}", userId);
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

    @Transactional(readOnly = true)
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
        return TokenResponse.builder()
                .user(principal.toUserResponse())
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
        String accessToken  = tokenProvider.generateAccessTokenFromUserId(
                user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = refreshTokenService.createRefreshToken(user.getId());
        return TokenResponse.builder()
                .user(UserResponse.fromUser(user))
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(tokenProvider.getAccessTokenExpirationMs() / 1000)
                .build();
    }
}
