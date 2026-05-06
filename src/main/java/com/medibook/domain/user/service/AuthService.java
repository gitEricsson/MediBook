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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final EmailOtpService emailOtpService;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new MediBookException("Email already registered", HttpStatus.CONFLICT, "EMAIL_TAKEN");
        }

        User user = User.builder()
                .email(request.getEmail().toLowerCase())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(Role.ROLE_PATIENT)
                .build();

        User saved = userRepository.save(user);
        log.info("New user registered: {}", saved.getEmail());
        return UserResponse.fromUser(saved);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", request.getEmail()));

        if (user.isTwoFactorEnabled()) {
            String otp = emailOtpService.generateAndStore(user.getEmail());
            emailOtpService.sendOtpEmail(user.getEmail(), otp);
            return TokenResponse.builder().twoFactorRequired(true).build();
        }

        return issueTokenPair(authentication);
    }

    @Transactional
    public TokenResponse verifyTwoFactor(TwoFactorVerifyRequest request) {
        emailOtpService.verify(request.getEmail(), request.getOtp());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", request.getEmail()));

        UserPrincipal principal = UserPrincipal.fromUser(user);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        return issueTokenPair(authentication);
    }

    @Transactional
    public void enableTwoFactor(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setTwoFactorEnabled(true);
        userRepository.save(user);
        log.info("2FA enabled for user {}", userId);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshTokenRequest request) {
        Long userId = refreshTokenService.validateAndGetUserId(request.getRefreshToken());
        String newRefreshToken = refreshTokenService.rotate(request.getRefreshToken());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        String accessToken = tokenProvider.generateAccessTokenFromUserId(
                userId, user.getEmail(), user.getRole().name());

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(900000L)
                .build();
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private TokenResponse issueTokenPair(Authentication auth) {
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        String accessToken = tokenProvider.generateAccessToken(auth);
        String refreshToken = refreshTokenService.createRefreshToken(principal.getId());
        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(900000L)
                .build();
    }
}
