package com.medibook.integration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.user.dto.*;
import com.medibook.common.response.ApiResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Auth Integration Tests")
class AuthIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    static String accessToken;
    static String refreshToken;
    @Test
    @Order(1)
    @DisplayName("POST /api/v1/auth/register — creates new patient")
    void register_success() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("integration@test.com");
        req.setPassword("Password1!");
        req.setFirstName("Integration");
        req.setLastName("Test");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.email").value("integration@test.com"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.password").doesNotExist());
    }
    @Test
    @Order(2)
    @DisplayName("POST /api/v1/auth/register — duplicate email returns 409")
    void register_duplicate_returns409() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("integration@test.com");
        req.setPassword("Password1!");
        req.setFirstName("Another");
        req.setLastName("User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_TAKEN"));
    }
    @Test
    @Order(3)
    @DisplayName("POST /api/v1/auth/login — valid credentials return JWT pair")
    void login_success() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("integration@test.com");
        req.setPassword("Password1!");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        var response = objectMapper.readTree(body);
        accessToken = response.get("data").get("accessToken").asText();
        refreshToken = response.get("data").get("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
    }
    @Test
    @Order(4)
    @DisplayName("POST /api/v1/auth/login — wrong password returns 401")
    void login_wrongPassword_returns401() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("integration@test.com");
        req.setPassword("WrongPassword!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
    @Test
    @Order(5)
    @DisplayName("POST /api/v1/auth/refresh — valid token returns new access token")
    void refresh_success() throws Exception {
        Assumptions.assumeTrue(refreshToken != null, "Login must succeed first");
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
    @Test
    @Order(6)
    @DisplayName("POST /api/v1/auth/register — invalid email returns 422")
    void register_invalidEmail_returns422() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("not-an-email");
        req.setPassword("Password1!");
        req.setFirstName("Bad");
        req.setLastName("Request");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }
    @Test
    @Order(7)
    @DisplayName("POST /api/v1/auth/register — password missing special character returns 422")
    void register_weakPassword_returns422() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("weakpass@test.com");
        req.setPassword("Password1");   // no special character
        req.setFirstName("Weak");
        req.setLastName("Pass");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }
    @Test
    @Order(8)
    @DisplayName("GET /api/v1/auth/me — valid token returns authenticated user profile")
    void me_withAuth_returnsUserProfile() throws Exception {
        Assumptions.assumeTrue(accessToken != null, "Login must succeed first");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("integration@test.com"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }
    @Test
    @Order(9)
    @DisplayName("GET /api/v1/auth/me — missing token returns 401")
    void me_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
    @Test
    @Order(10)
    @DisplayName("POST /api/v1/auth/logout — missing token returns 401")
    void logout_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"any-token\"}"))
                .andExpect(status().isUnauthorized());
    }
    @Test
    @Order(11)
    @DisplayName("POST /api/v1/auth/logout — authenticated user revokes session and gets 200")
    void logout_withFreshTokens_returns200() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("integration@test.com");
        req.setPassword("Password1!");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String freshAccessToken  = tree.get("data").get("accessToken").asText();
        String freshRefreshToken = tree.get("data").get("refreshToken").asText();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + freshAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + freshRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
    @Test
    @Order(12)
    @DisplayName("POST /api/v1/auth/email/resend — missing token returns 401")
    void resendVerification_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/resend"))
                .andExpect(status().isUnauthorized());
    }
    @Test
    @Order(13)
    @DisplayName("POST /api/v1/auth/email/resend — authenticated unverified user receives 200")
    void resendVerification_withAuth_returns200() throws Exception {
        Assumptions.assumeTrue(accessToken != null, "Login must succeed first");
        mockMvc.perform(post("/api/v1/auth/email/resend")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
    @Test
    @Order(14)
    @DisplayName("POST /api/v1/auth/forgot-password — existing email always returns 204")
    void forgotPassword_existingEmail_returns204() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("integration@test.com");
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }
    @Test
    @Order(15)
    @DisplayName("POST /api/v1/auth/forgot-password — unknown email still returns 204 (no enumeration)")
    void forgotPassword_unknownEmail_returns204() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("ghost@nowhere.com");
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }
    @Test
    @Order(16)
    @DisplayName("POST /api/v1/auth/forgot-password — invalid email format returns 422")
    void forgotPassword_invalidEmail_returns422() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("not-an-email");
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }
    @Test
    @Order(17)
    @DisplayName("POST /api/v1/auth/reset-password — token not in Redis returns 400")
    void resetPassword_invalidToken_returns400() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("bogus-reset-token");
        req.setNewPassword("NewPassword1!");
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RESET_TOKEN_INVALID"));
    }
    @Test
    @Order(18)
    @DisplayName("POST /api/v1/auth/reset-password — password without special character returns 422")
    void resetPassword_weakPassword_returns422() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("any-token");
        req.setNewPassword("weakpassword1");  // no special character
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
    }
    @Test
    @Order(19)
    @DisplayName("POST /api/v1/auth/email/verify — token not in Redis returns 400")
    void verifyEmail_invalidToken_returns400() throws Exception {
        EmailVerifyRequest req = new EmailVerifyRequest();
        req.setToken("bogus-verify-token");
        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VERIFY_TOKEN_INVALID"));
    }
    @Test
    @Order(20)
    @DisplayName("POST /api/v1/auth/2fa/verify — missing OTP returns 422")
    void twoFactor_missingOtp_returns422() throws Exception {
        TwoFactorVerifyRequest req = new TwoFactorVerifyRequest();
        req.setEmail("patient@medibook.com");
        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
    @Test
    @Order(21)
    @DisplayName("POST /api/v1/auth/2fa/verify — OTP wrong length returns 422")
    void twoFactor_otpWrongLength_returns422() throws Exception {
        TwoFactorVerifyRequest req = new TwoFactorVerifyRequest();
        req.setEmail("patient@medibook.com");
        req.setOtp("12345");  // only 5 digits — @Size(min=6, max=6) fails
        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("otp"));
    }
}
