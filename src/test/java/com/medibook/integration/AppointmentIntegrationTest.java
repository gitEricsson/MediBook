package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.dto.LoginRequest;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.messaging.producer.AppointmentEventProducer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Appointment Integration Tests")
class AppointmentIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.2")
            .withDatabaseName("medibook_appt_test")
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

    // Kafka is mocked out — appointment integration tests verify HTTP behaviour, not event publishing
    @MockBean AppointmentEventProducer eventProducer;

    @Autowired MockMvc               mockMvc;
    @Autowired ObjectMapper          objectMapper;
    @Autowired DepartmentRepository  departmentRepository;
    @Autowired UserRepository        userRepository;
    @Autowired DoctorRepository      doctorRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    // Shared state populated in @BeforeAll
    Long   doctorEntityId;
    String patientToken;
    String doctorToken;
    String adminToken;
    Long   appointmentId;   // captured after a successful booking

    @BeforeAll
    void setUpFixtures() throws Exception {
        Department dept = departmentRepository.save(
                Department.builder().name("Cardiology").code("CARD").build());

        User patientUser = userRepository.save(User.builder()
                .email("appt-patient@test.com")
                .password(passwordEncoder.encode("Password1!"))
                .firstName("Alice").lastName("Patient")
                .role(Role.ROLE_PATIENT).build());

        User docUser = userRepository.save(User.builder()
                .email("appt-doctor@test.com")
                .password(passwordEncoder.encode("Password1!"))
                .firstName("Bob").lastName("Doctor")
                .role(Role.ROLE_DOCTOR).build());

        userRepository.save(User.builder()
                .email("appt-admin@test.com")
                .password(passwordEncoder.encode("Password1!"))
                .firstName("Carol").lastName("Admin")
                .role(Role.ROLE_ADMIN).build());

        userRepository.save(User.builder()
                .email("appt-unrelated@test.com")
                .password(passwordEncoder.encode("Password1!"))
                .firstName("Dave").lastName("Nobody")
                .role(Role.ROLE_PATIENT).build());

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .user(docUser).department(dept)
                .licenseNumber("LIC-APPT-001")
                .specialization("Cardiology").build());
        doctorEntityId = doctor.getId();

        patientToken = loginAndGetToken("appt-patient@test.com", "Password1!");
        doctorToken  = loginAndGetToken("appt-doctor@test.com",  "Password1!");
        adminToken   = loginAndGetToken("appt-admin@test.com",   "Password1!");
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword(password);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }

    private AppointmentRequest buildRequest(Long doctorId, LocalDateTime at) {
        AppointmentRequest r = new AppointmentRequest();
        r.setDoctorId(doctorId);
        r.setScheduledAt(at);
        r.setDurationMins(30);
        r.setReason("Integration test booking");
        return r;
    }

    // ─── Book ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /api/v1/appointments — unauthenticated returns 401")
    void book_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest(doctorEntityId, LocalDateTime.now().plusDays(2)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/v1/appointments — null doctorId returns 422")
    void book_missingDoctorId_returns422() throws Exception {
        AppointmentRequest req = new AppointmentRequest();
        req.setScheduledAt(LocalDateTime.now().plusDays(2));

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("doctorId"));
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/v1/appointments — past date returns 422")
    void book_pastDate_returns422() throws Exception {
        AppointmentRequest req = buildRequest(doctorEntityId, LocalDateTime.now().minusDays(1));

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("scheduledAt"));
    }

    @Test
    @Order(4)
    @DisplayName("POST /api/v1/appointments — duration below 15 min returns 422")
    void book_durationBelowMin_returns422() throws Exception {
        AppointmentRequest req = buildRequest(doctorEntityId, LocalDateTime.now().plusDays(2));
        req.setDurationMins(10);

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("durationMins"));
    }

    @Test
    @Order(5)
    @DisplayName("POST /api/v1/appointments — duration above 480 min returns 422")
    void book_durationAboveMax_returns422() throws Exception {
        AppointmentRequest req = buildRequest(doctorEntityId, LocalDateTime.now().plusDays(2));
        req.setDurationMins(500);

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("durationMins"));
    }

    @Test
    @Order(6)
    @DisplayName("POST /api/v1/appointments — non-existent doctor returns 404")
    void book_doctorNotFound_returns404() throws Exception {
        AppointmentRequest req = buildRequest(9999L, LocalDateTime.now().plusDays(2));

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(7)
    @DisplayName("POST /api/v1/appointments — valid request creates PENDING appointment")
    void book_success_returns201() throws Exception {
        LocalDateTime slot = LocalDateTime.now().plusDays(5).withMinute(0).withSecond(0).withNano(0);
        AppointmentRequest req = buildRequest(doctorEntityId, slot);

        MvcResult result = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.doctorId").value(doctorEntityId))
                .andExpect(jsonPath("$.data.durationMins").value(30))
                .andReturn();

        appointmentId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
        assertThat(appointmentId).isPositive();

        // Verify DB state
        assertThat(appointmentRepository.findById(appointmentId)).isPresent()
                .hasValueSatisfying(a -> assertThat(a.getStatus()).isEqualTo(AppointmentStatus.PENDING));
    }

    @Test
    @Order(8)
    @DisplayName("POST /api/v1/appointments — same slot returns 409 SLOT_TAKEN")
    void book_slotConflict_returns409() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");

        // Fetch the slot that was just booked
        LocalDateTime slot = appointmentRepository.findById(appointmentId)
                .orElseThrow().getScheduledAt();

        AppointmentRequest req = buildRequest(doctorEntityId, slot);

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SLOT_TAKEN"));
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("GET /api/v1/appointments/{id} — non-existent returns 404")
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/999999")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(10)
    @DisplayName("GET /api/v1/appointments/{id} — returns full appointment details")
    void getById_existing_returns200() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");

        mockMvc.perform(get("/api/v1/appointments/" + appointmentId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(appointmentId))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.patientName").isNotEmpty())
                .andExpect(jsonPath("$.data.doctorName").isNotEmpty())
                .andExpect(jsonPath("$.data.departmentName").value("Cardiology"));
    }

    @Test
    @Order(11)
    @DisplayName("GET /api/v1/appointments/my — patient sees their booked appointment")
    void myAppointments_asPatient_returnsPage() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/my")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(12)
    @DisplayName("GET /api/v1/appointments/doctor/{id} — doctor role returns appointments page")
    void doctorAppointments_asDoctor_returnsPage() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/doctor/" + doctorEntityId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(13)
    @DisplayName("GET /api/v1/appointments/doctor/{id} — patient role returns 403")
    void doctorAppointments_asPatient_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/doctor/" + doctorEntityId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    // ─── Confirm ─────────────────────────────────────────────────────────────

    @Test
    @Order(14)
    @DisplayName("PATCH /api/v1/appointments/{id}/confirm — patient role returns 403")
    void confirm_asPatient_returns403() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");

        mockMvc.perform(patch("/api/v1/appointments/" + appointmentId + "/confirm")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(15)
    @DisplayName("PATCH /api/v1/appointments/{id}/confirm — assigned doctor returns 200 CONFIRMED")
    void confirm_asAssignedDoctor_returns200() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");

        mockMvc.perform(patch("/api/v1/appointments/" + appointmentId + "/confirm")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // Verify DB state
        assertThat(appointmentRepository.findById(appointmentId))
                .hasValueSatisfying(a -> assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED));
    }

    @Test
    @Order(16)
    @DisplayName("PATCH /api/v1/appointments/{id}/confirm — admin can confirm any appointment")
    void confirm_asAdmin_returns200() throws Exception {
        // Book a fresh appointment to confirm as admin
        LocalDateTime slot = LocalDateTime.now().plusDays(10).withMinute(0).withSecond(0).withNano(0);
        AppointmentRequest req = buildRequest(doctorEntityId, slot);

        MvcResult bookResult = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        Long newId = objectMapper.readTree(bookResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        mockMvc.perform(patch("/api/v1/appointments/" + newId + "/confirm")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    // ─── Cancel ──────────────────────────────────────────────────────────────

    @Test
    @Order(17)
    @DisplayName("PATCH /api/v1/appointments/{id}/confirm — already-CONFIRMED appointment returns 400")
    void confirm_nonPendingAppointment_returns400() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking and first confirm must succeed first");
        // appointmentId was already confirmed at @Order(15) — confirming again must be rejected
        mockMvc.perform(patch("/api/v1/appointments/" + appointmentId + "/confirm")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @Order(19)
    @DisplayName("PATCH /api/v1/appointments/{id}/cancel — unauthenticated returns 401")
    void cancel_unauthenticated_returns401() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");

        mockMvc.perform(patch("/api/v1/appointments/" + appointmentId + "/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(18)
    @DisplayName("PATCH /api/v1/appointments/{id}/cancel — admin cancels any appointment → 200 CANCELLED")
    void cancel_asAdmin_returns200() throws Exception {
        // Book a fresh appointment so we have something in PENDING/CONFIRMED state to cancel
        LocalDateTime slot = LocalDateTime.now().plusDays(15).withMinute(0).withSecond(0).withNano(0);
        AppointmentRequest req = buildRequest(doctorEntityId, slot);

        MvcResult bookResult = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        Long cancelId = objectMapper.readTree(bookResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        mockMvc.perform(patch("/api/v1/appointments/" + cancelId + "/cancel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(appointmentRepository.findById(cancelId))
                .hasValueSatisfying(a -> assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED));
    }

    @Test
    @Order(20)
    @DisplayName("PATCH /api/v1/appointments/{id}/cancel — COMPLETED appointment returns 400")
    void cancel_completedAppointment_returns400() throws Exception {
        // Force appointment to COMPLETED status directly in DB to test the guard
        LocalDateTime slot = LocalDateTime.now().plusDays(20).withMinute(0).withSecond(0).withNano(0);
        AppointmentRequest req = buildRequest(doctorEntityId, slot);

        MvcResult bookResult = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        Long completedId = objectMapper.readTree(bookResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // Set status to COMPLETED directly in the DB (no endpoint for this — it's a business transition)
        appointmentRepository.findById(completedId).ifPresent(a -> {
            a.setStatus(AppointmentStatus.COMPLETED);
            appointmentRepository.save(a);
        });

        mockMvc.perform(patch("/api/v1/appointments/" + completedId + "/cancel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @Order(21)
    @DisplayName("PATCH /api/v1/appointments/{id}/cancel — unrelated patient returns 403")
    void cancel_asUnrelatedPatient_returns403() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");

        // appointmentId belongs to "appt-patient@test.com"; login as a different patient
        String unrelatedToken = loginAndGetToken("appt-unrelated@test.com", "Password1!");

        mockMvc.perform(patch("/api/v1/appointments/" + appointmentId + "/cancel")
                        .header("Authorization", "Bearer " + unrelatedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }
}
