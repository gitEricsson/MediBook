package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.user.dto.*;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.domain.user.service.AuthService;
import com.medibook.domain.user.service.EmailVerificationService;
import com.medibook.domain.user.service.PasswordResetService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end happy-path flows for auth endpoints that require a real Redis token:
 *   - Password reset:    register → create token → POST /reset-password → login with new password
 *   - Email verification: register → create token → POST /email/verify → GET /me shows active user
 *   - 2FA:              register → enable 2FA → POST /login (challenge) → read OTP from Redis
 *                        → POST /2fa/verify → full token pair returned
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Auth End-to-End Flow Tests")
class AuthFlowIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.2")
            .withDatabaseName("medibook_flow_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host",     redis::getHost);
        registry.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc                          mockMvc;
    @Autowired ObjectMapper                     objectMapper;
    @Autowired PasswordResetService             passwordResetService;
    @Autowired EmailVerificationService         emailVerificationService;
    @Autowired AuthService                      authService;
    @Autowired UserRepository                   userRepository;
    @Autowired RedisTemplate<String, Object>    redisTemplate;

    // Shared across ordered tests within each flow
    static Long   userId;
    static String accessToken;
    static String refreshToken;

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 1: Password Reset
    // register → POST /forgot-password (no-op email) → create token via service
    // → POST /reset-password with real token → login with new password succeeds
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("[Reset Flow] Register base user")
    void resetFlow_register() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("reset-flow@test.com");
        req.setPassword("Password1!");
        req.setFirstName("Reset");
        req.setLastName("Flow");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        userId = tree.get("data").get("user").get("id").asLong();
        assertThat(userId).isPositive();
    }

    @Test
    @Order(2)
    @DisplayName("[Reset Flow] POST /forgot-password always returns 204")
    void resetFlow_forgotPassword_returns204() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("reset-flow@test.com");

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(3)
    @DisplayName("[Reset Flow] POST /reset-password — valid token changes password")
    void resetFlow_resetPassword_success() throws Exception {
        Assumptions.assumeTrue(userId != null, "Register must succeed first");

        // Bypass email: create a real token directly via the service
        String resetToken = passwordResetService.createToken(userId);

        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken(resetToken);
        req.setNewPassword("NewPassword1!");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(4)
    @DisplayName("[Reset Flow] Login with new password succeeds; old password rejected")
    void resetFlow_loginWithNewPassword_succeeds() throws Exception {
        LoginRequest newPw = new LoginRequest();
        newPw.setEmail("reset-flow@test.com");
        newPw.setPassword("NewPassword1!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newPw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        LoginRequest oldPw = new LoginRequest();
        oldPw.setEmail("reset-flow@test.com");
        oldPw.setPassword("Password1!");   // old password — must fail

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oldPw)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    @DisplayName("[Reset Flow] Replaying the same reset token returns 400 (atomic delete)")
    void resetFlow_replayToken_returns400() throws Exception {
        Assumptions.assumeTrue(userId != null, "Register must succeed first");

        String oneUseToken = passwordResetService.createToken(userId);

        ResetPasswordRequest first = new ResetPasswordRequest();
        first.setToken(oneUseToken);
        first.setNewPassword("YetAnother1!");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk());

        // second use of the same token must be rejected
        ResetPasswordRequest replay = new ResetPasswordRequest();
        replay.setToken(oneUseToken);
        replay.setNewPassword("Replay1234!");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replay)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RESET_TOKEN_INVALID"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 2: Email Verification
    // register → create verify token via service → POST /email/verify
    // → GET /me shows active=true
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("[Email Verify Flow] Register, then verify email with real token")
    void emailVerifyFlow_verifyEmail_success() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("verify-flow@test.com");
        req.setPassword("Password1!");
        req.setFirstName("Verify");
        req.setLastName("Flow");

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        var tree = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        Long verifyUserId = tree.get("data").get("user").get("id").asLong();
        accessToken       = tree.get("data").get("accessToken").asText();

        // Bypass email: create a real token via the service
        String verifyToken = emailVerificationService.createToken(verifyUserId);

        EmailVerifyRequest verifyReq = new EmailVerifyRequest();
        verifyReq.setToken(verifyToken);

        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Confirm the user is now active in the DB
        User user = userRepository.findByEmail("verify-flow@test.com").orElseThrow();
        assertThat(user.isActive()).isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("[Email Verify Flow] GET /me returns user profile after successful verification")
    void emailVerifyFlow_meEndpoint_returnsProfile() throws Exception {
        Assumptions.assumeTrue(accessToken != null, "Register must succeed first");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("verify-flow@test.com"));
    }

    @Test
    @Order(8)
    @DisplayName("[Email Verify Flow] Replaying verify token returns 400 (atomic delete)")
    void emailVerifyFlow_replayToken_returns400() throws Exception {
        User user = userRepository.findByEmail("verify-flow@test.com").orElseThrow();
        String oneUseToken = emailVerificationService.createToken(user.getId());

        EmailVerifyRequest first = new EmailVerifyRequest();
        first.setToken(oneUseToken);
        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk());

        // replay
        EmailVerifyRequest replay = new EmailVerifyRequest();
        replay.setToken(oneUseToken);
        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replay)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VERIFY_TOKEN_INVALID"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 3: Two-Factor Authentication
    // register → enable 2FA via service → POST /login (twoFactorRequired=true)
    // → read OTP from Redis → POST /2fa/verify → full token pair
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("[2FA Flow] Register, enable 2FA, login returns challenge")
    void twoFaFlow_login_returnsTwoFactorChallenge() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("twofa-flow@test.com");
        req.setPassword("Password1!");
        req.setFirstName("TwoFa");
        req.setLastName("Flow");

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        var tree = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        Long twoFaUserId = tree.get("data").get("user").get("id").asLong();

        // Enable 2FA directly via service (no admin endpoint needed)
        authService.enableTwoFactor(twoFaUserId);

        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail("twofa-flow@test.com");
        loginReq.setPassword("Password1!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.twoFactorRequired").value(true))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    @Order(10)
    @DisplayName("[2FA Flow] POST /2fa/verify — correct OTP returns full token pair")
    void twoFaFlow_verifyOtp_returnsTokenPair() throws Exception {
        // Trigger OTP generation by logging in again (stores OTP in Redis)
        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail("twofa-flow@test.com");
        loginReq.setPassword("Password1!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.twoFactorRequired").value(true));

        // Read the OTP directly from Redis (key = "otp:code:{email}")
        Object storedOtp = redisTemplate.opsForValue().get("otp:code:twofa-flow@test.com");
        assertThat(storedOtp).as("OTP must be stored in Redis after login").isNotNull();

        TwoFactorVerifyRequest verifyReq = new TwoFactorVerifyRequest();
        verifyReq.setEmail("twofa-flow@test.com");
        verifyReq.setOtp(storedOtp.toString());

        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.twoFactorRequired").doesNotExist());
    }

    @Test
    @Order(11)
    @DisplayName("[2FA Flow] POST /2fa/verify — wrong OTP returns 401")
    void twoFaFlow_wrongOtp_returns401() throws Exception {
        // Trigger a fresh OTP so the key exists in Redis
        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail("twofa-flow@test.com");
        loginReq.setPassword("Password1!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk());

        TwoFactorVerifyRequest verifyReq = new TwoFactorVerifyRequest();
        verifyReq.setEmail("twofa-flow@test.com");
        verifyReq.setOtp("000000");  // deliberately wrong

        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow 4: Refresh Token Lifecycle
    // login → use token → logout (revoke) → replay refresh token → 401
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(12)
    @DisplayName("[Token Lifecycle] Login and capture tokens")
    void tokenLifecycle_login() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("lifecycle-flow@test.com");
        reg.setPassword("Password1!");
        reg.setFirstName("Lifecycle");
        reg.setLastName("Flow");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail("lifecycle-flow@test.com");
        loginReq.setPassword("Password1!");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        accessToken  = tree.get("data").get("accessToken").asText();
        refreshToken = tree.get("data").get("refreshToken").asText();

        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
    }

    @Test
    @Order(13)
    @DisplayName("[Token Lifecycle] Logout revokes the refresh token")
    void tokenLifecycle_logout_revokesRefreshToken() throws Exception {
        Assumptions.assumeTrue(accessToken != null && refreshToken != null, "Login must succeed first");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(14)
    @DisplayName("[Token Lifecycle] Replaying revoked refresh token returns 401")
    void tokenLifecycle_revokedRefreshToken_returns401() throws Exception {
        Assumptions.assumeTrue(refreshToken != null, "Logout must succeed first");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
