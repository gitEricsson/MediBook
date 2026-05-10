package com.medibook.integration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.notification.dto.NotificationResponse;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.domain.user.dto.LoginRequest;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.messaging.producer.AppointmentEventProducer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/**
 * Integration tests for UserController + NotificationController.
 *
 * NotificationService is mocked because it depends on Cassandra.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("User + Notification Integration Tests")
class UserIntegrationTest extends IntegrationTestSupport {
    @MockBean AppointmentEventProducer eventProducer;
    @MockBean NotificationService      notificationService;   // avoids Cassandra dependency
    @Autowired MockMvc         mockMvc;
    @Autowired ObjectMapper    objectMapper;
    @Autowired UserRepository  userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    String patientToken;
    String adminToken;
    Long   patientUserId;
    Long   targetUserId;   // a second user for admin operations
    @BeforeAll
    void setUpFixtures() throws Exception {
        User patient = userRepository.save(User.builder()
                .email("user-it-patient@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Alice").lastName("User").role(Role.ROLE_PATIENT).build());
        patientUserId = patient.getId();
        userRepository.save(User.builder()
                .email("user-it-admin@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Carol").lastName("Admin").role(Role.ROLE_ADMIN).build());
        User target = userRepository.save(User.builder()
                .email("user-it-target@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Dave").lastName("Target").role(Role.ROLE_PATIENT).build());
        targetUserId = target.getId();
        patientToken = loginAndGetToken("user-it-patient@test.com", "Password1!");
        adminToken   = loginAndGetToken("user-it-admin@test.com",   "Password1!");
        when(notificationService.getRecent(anyLong())).thenReturn(List.of());
        when(notificationService.getUnread(anyLong())).thenReturn(List.of());
    }
    private String loginAndGetToken(String email, String password) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }
    @Test @Order(1)
    @DisplayName("GET /api/v1/users/me — unauthenticated returns 401")
    void getMyProfile_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }
    @Test @Order(2)
    @DisplayName("GET /api/v1/users/me — returns authenticated user's profile")
    void getMyProfile_authenticated_returnsProfile() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("user-it-patient@test.com"))
                .andExpect(jsonPath("$.data.fullName").value("Alice User"))
                .andExpect(jsonPath("$.data.role").value("ROLE_PATIENT"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }
    @Test @Order(3)
    @DisplayName("GET /api/v1/users/{id} — patient role returns 403")
    void getUserById_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + targetUserId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }
    @Test @Order(4)
    @DisplayName("GET /api/v1/users/{id} — admin returns user profile")
    void getUserById_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + targetUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(targetUserId))
                .andExpect(jsonPath("$.data.email").value("user-it-target@test.com"));
    }
    @Test @Order(5)
    @DisplayName("GET /api/v1/users/{id} — admin, not found returns 404")
    void getUserById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
    @Test @Order(6)
    @DisplayName("GET /api/v1/users — patient role returns 403")
    void listUsers_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }
    @Test @Order(7)
    @DisplayName("GET /api/v1/users — admin returns paginated user list")
    void listUsers_admin_returns200WithPage() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }
    @Test @Order(8)
    @DisplayName("POST /api/v1/users/me/2fa/enable — unauthenticated returns 401")
    void enableTwoFactor_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/2fa/enable"))
                .andExpect(status().isUnauthorized());
    }
    @Test @Order(9)
    @DisplayName("POST /api/v1/users/me/2fa/enable — authenticated user enables 2FA → 200")
    void enableTwoFactor_authenticated_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/2fa/enable")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(userRepository.findById(patientUserId))
                .hasValueSatisfying(u -> assertThat(u.isTwoFactorEnabled()).isTrue());
    }
    @Test @Order(10)
    @DisplayName("PATCH /api/v1/users/{id}/disable — patient role returns 403")
    void disableUser_patientRole_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/users/" + targetUserId + "/disable")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }
    @Test @Order(11)
    @DisplayName("PATCH /api/v1/users/{id}/disable — admin disables user → 200, enabled=false in DB")
    void disableUser_admin_returns200() throws Exception {
        mockMvc.perform(patch("/api/v1/users/" + targetUserId + "/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(userRepository.findById(targetUserId))
                .hasValueSatisfying(u -> assertThat(u.isEnabled()).isFalse());
    }
    @Test @Order(12)
    @DisplayName("PATCH /api/v1/users/{id}/disable — user not found returns 404")
    void disableUser_notFound_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/users/999999/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
    @Test @Order(13)
    @DisplayName("GET /api/v1/notifications — unauthenticated returns 401")
    void getNotifications_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }
    @Test @Order(14)
    @DisplayName("GET /api/v1/notifications — authenticated returns recent notifications list")
    void getNotifications_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
    @Test @Order(15)
    @DisplayName("GET /api/v1/notifications/unread — authenticated returns unread list")
    void getUnreadNotifications_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/unread")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
    @Test @Order(16)
    @DisplayName("GET /api/v1/notifications/unread — notification service returns mock data")
    void getUnreadNotifications_returnsMockedData() throws Exception {
        NotificationResponse n = NotificationResponse.builder()
                .title("Test").message("Test msg")
                .type("APPOINTMENT_BOOKED").build();
        when(notificationService.getUnread(anyLong())).thenReturn(List.of(n));
        mockMvc.perform(get("/api/v1/notifications/unread")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].title").value("Test"));
    }
}