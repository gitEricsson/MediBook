package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.CancelRequest;
import com.medibook.domain.appointment.dto.RescheduleRequest;
import com.medibook.domain.appointment.dto.TransitionRequest;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 2 Appointment Integration Tests")
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
    @DisplayName("POST /api/v1/appointments — Creates appointment and returns confirmation code")
    @WithMockUser(username = "1", roles = {"PATIENT"}) // Assuming user ID 1 exists as patient in seed
    void book_success() throws Exception {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L); // Assuming doctor ID 1 exists in test DB
        req.setScheduledAt(LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0));
        req.setType(AppointmentType.IN_PERSON);
        req.setDurationMins(30);

        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.confirmationCode").exists())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/v1/appointments — 409 SLOT_TAKEN on concurrent booking")
    @WithMockUser(username = "2", roles = {"PATIENT"})
    void book_conflict_returns409() throws Exception {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        // Using the exact same time as the first test to trigger a conflict
        req.setScheduledAt(LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0));
        req.setType(AppointmentType.IN_PERSON);

        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SLOT_TAKEN"));
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/v1/me/schedule — Doctor fetches daily view")
    @WithMockUser(username = "1", roles = {"DOCTOR"}) // Assuming user ID 1 is linked to doctor ID 1
    void getDailySchedule() throws Exception {
        LocalDate targetDate = LocalDate.now().plusDays(2);
        mockMvc.perform(get("/api/v1/me/schedule")
                        .param("date", targetDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(targetDate.toString()))
                .andExpect(jsonPath("$.data.appointments").isArray());
    }

    @Test
    @Order(4)
    @DisplayName("POST /api/v1/appointments/1/transition — Doctor transitions to CONFIRMED")
    @WithMockUser(username = "1", roles = {"DOCTOR"})
    void transition_toConfirmed() throws Exception {
        TransitionRequest req = new TransitionRequest();
        req.setTo(AppointmentStatus.CONFIRMED);

        mockMvc.perform(post("/api/v1/appointments/1/transition") // Assumes appointment ID 1 was created in test 1
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @Order(5)
    @DisplayName("POST /api/v1/appointments/1/cancel — Patient cancels appointment successfully outside notice window")
    @WithMockUser(username = "1", roles = {"PATIENT"})
    void cancel_patient_success() throws Exception {
        CancelRequest req = new CancelRequest();
        req.setReason("Changed my mind");

        mockMvc.perform(post("/api/v1/appointments/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @Order(6)
    @DisplayName("POST /api/v1/appointments/1/calendar.ics — Returns ICS blob")
    @WithMockUser(username = "1", roles = {"PATIENT"})
    void getIcs() throws Exception {
        mockMvc.perform(post("/api/v1/appointments/1/calendar.ics"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar"));
    }

    @Test
    @Order(7)
    @DisplayName("GET /api/v1/patients/1/summary — Doctor fetches patient history")
    @WithMockUser(username = "1", roles = {"DOCTOR"})
    void getPatientSummary() throws Exception {
        mockMvc.perform(get("/api/v1/patients/1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.patientId").value(1));
    }
}
