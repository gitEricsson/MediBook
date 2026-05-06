package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Appointment Integration Tests")
class AppointmentIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.2")
            .withDatabaseName("medibook_test")
            .withUsername("test")
            .withPassword("test");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @Order(1)
    @DisplayName("POST /api/v1/appointments — unauthenticated returns 401")
    void book_unauthenticated_returns401() throws Exception {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(LocalDateTime.now().plusDays(2));

        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/v1/appointments — past date returns 422")
    @WithMockUser(username = "1", roles = {"PATIENT"})
    void book_pastDate_returns422() throws Exception {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(LocalDateTime.now().minusDays(1));  // past date

        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/v1/appointments/{id} — non-existent returns 404")
    @WithMockUser(roles = {"PATIENT"})
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(4)
    @DisplayName("GET /api/v1/appointments/my — authenticated patient gets empty list")
    @WithMockUser(username = "1", roles = {"PATIENT"})
    void myAppointments_authenticated_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(5)
    @DisplayName("PATCH /api/v1/appointments/{id}/confirm — patient role returns 403")
    @WithMockUser(roles = {"PATIENT"})
    void confirm_patientRole_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/appointments/1/confirm"))
                .andExpect(status().isForbidden());
    }
}
