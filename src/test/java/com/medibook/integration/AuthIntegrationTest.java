package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.user.dto.LoginRequest;
import com.medibook.domain.user.dto.RegisterRequest;
import com.medibook.domain.user.dto.TokenResponse;
import com.medibook.common.response.ApiResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Auth Integration Tests")
class AuthIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.2")
            .withDatabaseName("medibook_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    static String accessToken;
    static String refreshToken;

    // ─── Register ────────────────────────────────────────────────────────────

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
                .andExpect(jsonPath("$.data.email").value("integration@test.com"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
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

    // ─── Login ───────────────────────────────────────────────────────────────

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

    // ─── Refresh ─────────────────────────────────────────────────────────────

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

    // ─── Validation ──────────────────────────────────────────────────────────

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
}
