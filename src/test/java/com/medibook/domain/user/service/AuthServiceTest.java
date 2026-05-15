package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.user.dto.*;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.JwtTokenProvider;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;
import com.medibook.common.exception.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — Unit Tests")
class AuthServiceTest {

    @Mock UserRepository            userRepository;
    @Mock PasswordEncoder           passwordEncoder;
    @Mock AuthenticationManager     authenticationManager;
    @Mock JwtTokenProvider          tokenProvider;
    @Mock RefreshTokenService       refreshTokenService;
    @Mock EmailOtpService           emailOtpService;
    @Mock PasswordResetService      passwordResetService;
    @Mock EmailVerificationService  emailVerificationService;
    @Mock com.medibook.messaging.producer.AppointmentEventProducer eventProducer;
    @Mock com.medibook.infrastructure.metrics.TokenMetrics         tokenMetrics;
    @Mock com.medibook.domain.user.service.SessionTimeoutService   sessionTimeoutService;
    @Mock com.medibook.domain.doctor.repository.DoctorRepository   doctorRepository;

    @InjectMocks AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .email("patient@medibook.com")
                .password("encoded-password")
                .firstName("John")
                .lastName("Doe")
                .role(Role.ROLE_PATIENT)
                .enabled(true)
                .isActive(true)
                .twoFactorEnabled(false)
                .build();
    }


    @Test
    @DisplayName("register — success persists user and returns profile without issuing tokens")
    void register_success() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new@medibook.com");
        req.setPassword("Password1!");
        req.setFirstName("Jane");
        req.setLastName("Smith");

        when(userRepository.existsByEmailIgnoreCase("new@medibook.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userRepository.save(any())).thenReturn(testUser);
        when(emailVerificationService.createToken(anyLong())).thenReturn("verify-token");

        TokenResponse response = authService.register(req);

        assertThat(response.getAccessToken()).isNull();
        assertThat(response.getRefreshToken()).isNull();
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getEmail()).isEqualTo(testUser.getEmail());
        assertThat(response.getTokenType()).isNull();
        verify(userRepository).save(any(User.class));
        verify(emailVerificationService).createToken(testUser.getId());
        verify(emailVerificationService).sendVerificationEmail(eq(testUser.getEmail()), eq("verify-token"));
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("register — duplicate email throws CONFLICT")
    void register_duplicateEmail_throwsConflict() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("patient@medibook.com");
        req.setPassword("Password1!");
        req.setFirstName("Jane");
        req.setLastName("Smith");

        when(userRepository.existsByEmailIgnoreCase("patient@medibook.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }


    @Test
    @DisplayName("login — no 2FA, returns full JWT pair without extra DB query")
    void login_noTwoFactor_returnsTokenPair() {
        LoginRequest req = new LoginRequest();
        req.setEmail("patient@medibook.com");
        req.setPassword("Password1!");

        UserPrincipal principal = UserPrincipal.fromUser(testUser);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(tokenProvider.generateAccessToken(auth)).thenReturn("access-token");
        when(tokenProvider.getAccessTokenExpirationMs()).thenReturn(900_000L);
        when(refreshTokenService.createRefreshToken(1L)).thenReturn("refresh-token");
        lenient().when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(testUser));

        TokenResponse response = authService.login(req);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getTwoFactorRequired()).isNull();
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getEmail()).isEqualTo("patient@medibook.com");
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("login — 2FA enabled, returns challenge with twoFactorRequired=true")
    void login_twoFactorEnabled_returnsChallenge() {
        testUser.setTwoFactorEnabled(true);
        LoginRequest req = new LoginRequest();
        req.setEmail("patient@medibook.com");
        req.setPassword("Password1!");

        UserPrincipal principal = UserPrincipal.fromUser(testUser);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(emailOtpService.generateAndStore("patient@medibook.com")).thenReturn("123456");

        TokenResponse response = authService.login(req);

        assertThat(response.getTwoFactorRequired()).isTrue();
        assertThat(response.getAccessToken()).isNull();
        assertThat(response.getRefreshToken()).isNull();
        verify(emailOtpService).sendOtpEmail("patient@medibook.com", "123456");
    }

    @Test
    @DisplayName("login — email not verified, throws EMAIL_NOT_VERIFIED")
    void login_emailNotVerified_throwsEmailNotVerified() {
        testUser.setActive(false);
        LoginRequest req = new LoginRequest();
        req.setEmail("patient@medibook.com");
        req.setPassword("Password1!");

        UserPrincipal principal = UserPrincipal.fromUser(testUser);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(auth);

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mEx = (MediBookException) ex;
                    assertThat(mEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(mEx.getErrorCode()).isEqualTo("EMAIL_NOT_VERIFIED");
                });
    }

    @Test
    @DisplayName("login — test user with unverified email bypasses check")
    void login_testUser_bypassesEmailVerification() {
        testUser.setEmail("patient@test.com");
        testUser.setActive(false);
        LoginRequest req = new LoginRequest();
        req.setEmail("patient@test.com");
        req.setPassword("Password1!");

        UserPrincipal principal = UserPrincipal.fromUser(testUser);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(tokenProvider.generateAccessToken(auth)).thenReturn("access-token");
        when(tokenProvider.getAccessTokenExpirationMs()).thenReturn(900_000L);
        when(refreshTokenService.createRefreshToken(1L)).thenReturn("refresh-token");

        TokenResponse response = authService.login(req);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
    }


    @Test
    @DisplayName("refresh — valid token rotates atomically and issues new access token")
    void refresh_valid_rotatesToken() {
        var rotationResult = new RefreshTokenService.RotationResult(1L, "new-refresh");
        when(refreshTokenService.rotate("old-token")).thenReturn(rotationResult);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(tokenProvider.generateAccessTokenFromUserId(1L, "patient@medibook.com", "ROLE_PATIENT"))
                .thenReturn("new-access");
        when(tokenProvider.getAccessTokenExpirationMs()).thenReturn(900_000L);

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("old-token");

        TokenResponse response = authService.refresh(req);

        assertThat(response.getAccessToken()).isEqualTo("new-access");
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh");
        verify(refreshTokenService, never()).validateAndGetUserId(anyString());
    }


    @Test
    @DisplayName("logout — delegates revoke to RefreshTokenService")
    void logout_revokesToken() {
        authService.logout("some-token");
        verify(refreshTokenService).revoke("some-token");
    }


    @Test
    @DisplayName("forgotPassword — existing user triggers reset email")
    void forgotPassword_existingUser_sendsEmail() {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("patient@medibook.com");

        when(userRepository.findByEmail("patient@medibook.com")).thenReturn(Optional.of(testUser));
        when(passwordResetService.createToken(1L)).thenReturn("reset-token");

        authService.forgotPassword(req);

        verify(passwordResetService).createToken(1L);
        verify(passwordResetService).sendResetEmail("patient@medibook.com", "reset-token");
    }

    @Test
    @DisplayName("forgotPassword — unknown email is silently ignored (no enumeration)")
    void forgotPassword_unknownEmail_noOp() {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("nobody@medibook.com");

        when(userRepository.findByEmail("nobody@medibook.com")).thenReturn(Optional.empty());

        authService.forgotPassword(req);

        verify(passwordResetService, never()).createToken(anyLong());
        verify(passwordResetService, never()).sendResetEmail(anyString(), anyString());
    }


    @Test
    @DisplayName("resendVerificationEmail — already verified throws BAD_REQUEST")
    void resendVerification_alreadyVerified_throws() {
        testUser.setActive(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.resendVerificationEmail(1L))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("resendVerificationEmail — unverified user triggers fresh token and email dispatch")
    void resendVerification_notYetVerified_sendsEmail() {
        testUser.setActive(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(emailVerificationService.createToken(1L)).thenReturn("fresh-verify-token");

        authService.resendVerificationEmail(1L);

        verify(emailVerificationService).createToken(1L);
        verify(emailVerificationService).sendVerificationEmail(testUser.getEmail(), "fresh-verify-token");
    }

    @Test
    @DisplayName("resendVerificationEmail — unknown userId throws NOT_FOUND")
    void resendVerification_userNotFound_throwsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resendVerificationEmail(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }


    @Test
    @DisplayName("verifyTwoFactor — valid OTP issues full token pair without extra DB query")
    void verifyTwoFactor_validOtp_returnsTokenPair() {
        TwoFactorVerifyRequest req = new TwoFactorVerifyRequest();
        req.setEmail("patient@medibook.com");
        req.setOtp("123456");

        when(userRepository.findByEmail("patient@medibook.com")).thenReturn(Optional.of(testUser));
        when(tokenProvider.generateAccessTokenFromUserId(1L, "patient@medibook.com", "ROLE_PATIENT"))
                .thenReturn("access-token");
        when(tokenProvider.getAccessTokenExpirationMs()).thenReturn(900_000L);
        when(refreshTokenService.createRefreshToken(1L)).thenReturn("refresh-token");

        TokenResponse response = authService.verifyTwoFactor(req);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getUser().getEmail()).isEqualTo("patient@medibook.com");
        verify(emailOtpService).verify("patient@medibook.com", "123456");
    }

    @Test
    @DisplayName("verifyTwoFactor — wrong OTP propagates UNAUTHORIZED and skips user lookup")
    void verifyTwoFactor_wrongOtp_propagatesUnauthorized() {
        TwoFactorVerifyRequest req = new TwoFactorVerifyRequest();
        req.setEmail("patient@medibook.com");
        req.setOtp("000000");

        doThrow(new MediBookException("Invalid OTP", HttpStatus.UNAUTHORIZED, "OTP_INVALID"))
                .when(emailOtpService).verify("patient@medibook.com", "000000");

        assertThatThrownBy(() -> authService.verifyTwoFactor(req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(userRepository, never()).findByEmail(anyString());
    }


    @Test
    @DisplayName("resetPassword — valid token encodes new password and persists user")
    void resetPassword_validToken_encodesAndSaves() {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("valid-reset-token");
        req.setNewPassword("NewPassword1!");

        when(passwordResetService.validateAndConsume("valid-reset-token")).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.encode("NewPassword1!")).thenReturn("new-encoded");

        authService.resetPassword(req);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("new-encoded");
        verify(refreshTokenService).revokeAllForUser(1L);
    }

    @Test
    @DisplayName("resetPassword — expired or invalid token throws BAD_REQUEST, skips DB lookup")
    void resetPassword_invalidToken_throws() {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("stale-token");
        req.setNewPassword("NewPassword1!");

        when(passwordResetService.validateAndConsume("stale-token"))
                .thenThrow(new MediBookException("Invalid or expired password reset link",
                        HttpStatus.BAD_REQUEST, "RESET_TOKEN_INVALID"));

        assertThatThrownBy(() -> authService.resetPassword(req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(userRepository, never()).findById(anyLong());
    }


    @Test
    @DisplayName("verifyEmail — valid token flips user active=true and persists")
    void verifyEmail_validToken_activatesUser() {
        EmailVerifyRequest req = new EmailVerifyRequest();
        req.setToken("valid-verify-token");

        when(emailVerificationService.validateAndConsume("valid-verify-token")).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        authService.verifyEmail(req);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    @DisplayName("verifyEmail — invalid token throws BAD_REQUEST without touching user store")
    void verifyEmail_invalidToken_throws() {
        EmailVerifyRequest req = new EmailVerifyRequest();
        req.setToken("bad-verify-token");

        when(emailVerificationService.validateAndConsume("bad-verify-token"))
                .thenThrow(new MediBookException("Invalid or expired email verification link",
                        HttpStatus.BAD_REQUEST, "VERIFY_TOKEN_INVALID"));

        assertThatThrownBy(() -> authService.verifyEmail(req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(userRepository, never()).findById(anyLong());
    }


    @Test
    @DisplayName("refresh — user deleted between token rotation and DB lookup throws NOT_FOUND")
    void refresh_userNotFound_throwsNotFound() {
        var rotationResult = new RefreshTokenService.RotationResult(99L, "new-refresh");
        when(refreshTokenService.rotate("orphan-token")).thenReturn(rotationResult);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("orphan-token");

        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(ResourceNotFoundException.class);
    }


    @Test
    @DisplayName("register — email is normalised to lower-case before persistence")
    void register_emailNormalized_toLowerCase() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("UPPER@MEDIBOOK.COM");
        req.setPassword("Password1!");
        req.setFirstName("Jane");
        req.setLastName("Smith");

        when(userRepository.existsByEmailIgnoreCase("upper@medibook.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userRepository.save(any())).thenReturn(testUser);
        when(emailVerificationService.createToken(anyLong())).thenReturn("verify-token");

        authService.register(req);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("upper@medibook.com");
    }
}
