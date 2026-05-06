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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — Unit Tests")
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthenticationManager authenticationManager;
    @Mock JwtTokenProvider tokenProvider;
    @Mock RefreshTokenService refreshTokenService;
    @Mock EmailOtpService emailOtpService;

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
                .twoFactorEnabled(false)
                .build();
    }

    // ─── Register ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("register — success creates user and returns response")
    void register_success() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new@medibook.com");
        req.setPassword("Password1!");
        req.setFirstName("Jane");
        req.setLastName("Smith");

        when(userRepository.existsByEmail("new@medibook.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userRepository.save(any())).thenReturn(testUser);

        UserResponse response = authService.register(req);

        assertThat(response).isNotNull();
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("register — duplicate email throws CONFLICT")
    void register_duplicateEmail_throwsConflict() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("patient@medibook.com");
        req.setPassword("Password1!");
        req.setFirstName("Jane");
        req.setLastName("Smith");

        when(userRepository.existsByEmail("patient@medibook.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login — no 2FA, returns full JWT pair")
    void login_noTwoFactor_returnsTokenPair() {
        LoginRequest req = new LoginRequest();
        req.setEmail("patient@medibook.com");
        req.setPassword("Password1!");

        UserPrincipal principal = UserPrincipal.fromUser(testUser);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userRepository.findByEmail("patient@medibook.com")).thenReturn(Optional.of(testUser));
        when(tokenProvider.generateAccessToken(auth)).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(1L)).thenReturn("refresh-token");

        TokenResponse response = authService.login(req);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getTwoFactorRequired()).isNull();
    }

    @Test
    @DisplayName("login — 2FA enabled, returns challenge with twoFactorRequired=true")
    void login_twoFactorEnabled_returnChallenge() {
        testUser.setTwoFactorEnabled(true);
        LoginRequest req = new LoginRequest();
        req.setEmail("patient@medibook.com");
        req.setPassword("Password1!");

        UserPrincipal principal = UserPrincipal.fromUser(testUser);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userRepository.findByEmail("patient@medibook.com")).thenReturn(Optional.of(testUser));
        when(emailOtpService.generateAndStore("patient@medibook.com")).thenReturn("123456");

        TokenResponse response = authService.login(req);

        assertThat(response.getTwoFactorRequired()).isTrue();
        assertThat(response.getAccessToken()).isNull();
        verify(emailOtpService).sendOtpEmail("patient@medibook.com", "123456");
    }

    // ─── Refresh ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh — valid token rotates and issues new access token")
    void refresh_valid_rotatesToken() {
        when(refreshTokenService.validateAndGetUserId("old-token")).thenReturn(1L);
        when(refreshTokenService.rotate("old-token")).thenReturn("new-refresh");
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(tokenProvider.generateAccessTokenFromUserId(1L, "patient@medibook.com", "ROLE_PATIENT"))
                .thenReturn("new-access");

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("old-token");

        TokenResponse response = authService.refresh(req);

        assertThat(response.getAccessToken()).isEqualTo("new-access");
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh");
    }

    // ─── Logout ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout — delegates revoke to RefreshTokenService")
    void logout_revokesToken() {
        authService.logout("some-token");
        verify(refreshTokenService).revoke("some-token");
    }
}
