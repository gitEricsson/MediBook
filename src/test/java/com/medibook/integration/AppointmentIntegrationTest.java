package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.CancelRequest;
import com.medibook.domain.appointment.dto.RescheduleRequest;
import com.medibook.domain.appointment.dto.TransitionRequest;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.appointment.service.AppointmentHoldService;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.patient.service.PatientHistoryService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests for all appointment endpoints.
 *
 * Test data is created once in @BeforeAll using real JPA repositories.
 * Kafka events are isolated via @MockBean. AppointmentHoldService runs
 * against the real Redis container so the hold → book → release flow is
 * exercised end-to-end.
 */
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
            .withDatabaseName("medibook_appt_it")
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

    @MockBean AppointmentEventProducer eventProducer;

    @MockBean PatientHistoryService patientHistoryService;

    @Autowired MockMvc               mockMvc;
    @Autowired ObjectMapper          objectMapper;
    @Autowired DepartmentRepository  departmentRepository;
    @Autowired UserRepository        userRepository;
    @Autowired DoctorRepository      doctorRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired AppointmentHoldService holdService;
    @Autowired PasswordEncoder       passwordEncoder;

    Long   doctorEntityId;
    Long   doctorUserId;
    String patientToken;
    String doctorToken;
    String adminToken;
    Long   appointmentId;     // created by @Order(4)
    Long   rescheduleApptId;  // created by @Order(9)

    @BeforeAll
    void setUpFixtures() throws Exception {
        Department dept = departmentRepository.save(
                Department.builder().name("General Medicine").code("GM01").build());

        User patientUser = userRepository.save(User.builder()
                .email("it-patient@test.com")
                .password(passwordEncoder.encode("Password1!"))
                .firstName("Alice").lastName("Patient")
                .role(Role.ROLE_PATIENT).build());

        User docUser = userRepository.save(User.builder()
                .email("it-doctor@test.com")
                .password(passwordEncoder.encode("Password1!"))
                .firstName("Bob").lastName("Doctor")
                .role(Role.ROLE_DOCTOR).build());
        doctorUserId = docUser.getId();

        userRepository.save(User.builder()
                .email("it-admin@test.com")
                .password(passwordEncoder.encode("Password1!"))
                .firstName("Carol").lastName("Admin")
                .role(Role.ROLE_ADMIN).build());

        userRepository.save(User.builder()
                .email("it-unrelated@test.com")
                .password(passwordEncoder.encode("Password1!"))
                .firstName("Dave").lastName("Nobody")
                .role(Role.ROLE_PATIENT).build());

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .user(docUser).department(dept)
                .licenseNumber("LIC-IT-777")
                .specialization("General").build());
        doctorEntityId = doctor.getId();

        patientToken = loginAndGetToken("it-patient@test.com", "Password1!");
        doctorToken  = loginAndGetToken("it-doctor@test.com",  "Password1!");
        adminToken   = loginAndGetToken("it-admin@test.com",   "Password1!");

        when(patientHistoryService.getPatientSummary(anyLong()))
                .thenReturn(com.medibook.domain.patient.dto.PatientSummaryResponse.builder()
                        .patientId(patientUser.getId()).fullName("Alice Patient").build());
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

    private AppointmentRequest buildReq(LocalDateTime slot) {
        AppointmentRequest r = new AppointmentRequest();
        r.setDoctorId(doctorEntityId);
        r.setScheduledAt(slot);
        r.setType(AppointmentType.IN_PERSON);
        r.setDurationMins(30);
        r.setReason("Integration test");
        return r;
    }


    @Test
    @Order(1)
    @DisplayName("POST /api/v1/appointments — unauthenticated returns 401")
    void book_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildReq(LocalDateTime.now().plusDays(3)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/v1/appointments — doctor role returns 403 (PATIENT only)")
    void book_doctorRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildReq(LocalDateTime.now().plusDays(3)))))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/v1/appointments — past date returns 422 VALIDATION_FAILED")
    void book_pastDate_returns422() throws Exception {
        AppointmentRequest req = buildReq(LocalDateTime.now().minusDays(1));
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
    @DisplayName("POST /api/v1/appointments — missing doctorId returns 422")
    void book_missingDoctorId_returns422() throws Exception {
        AppointmentRequest req = new AppointmentRequest();
        req.setScheduledAt(LocalDateTime.now().plusDays(3));
        req.setType(AppointmentType.IN_PERSON);
        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("doctorId"));
    }

    @Test
    @Order(5)
    @DisplayName("POST /api/v1/appointments — duration below 15 min returns 422")
    void book_durationBelowMin_returns422() throws Exception {
        AppointmentRequest req = buildReq(LocalDateTime.now().plusDays(3));
        req.setDurationMins(5);
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
    void book_unknownDoctor_returns404() throws Exception {
        AppointmentRequest req = buildReq(LocalDateTime.now().plusDays(3));
        req.setDoctorId(99999L);
        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }


    @Test
    @Order(7)
    @DisplayName("POST /api/v1/appointments — creates PENDING appointment with confirmationCode")
    void book_success_createsPendingAppointment() throws Exception {
        LocalDateTime slot = LocalDateTime.now().plusDays(7).withMinute(0).withSecond(0).withNano(0);
        MvcResult result = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildReq(slot))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.confirmationCode").isNotEmpty())
                .andExpect(jsonPath("$.data.departmentName").value("General Medicine"))
                .andReturn();

        appointmentId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
        assertThat(appointmentId).isPositive();

        assertThat(appointmentRepository.findById(appointmentId))
                .hasValueSatisfying(a -> {
                    assertThat(a.getStatus()).isEqualTo(AppointmentStatus.PENDING);
                    assertThat(a.getDepartment()).isNotNull();
                    assertThat(a.getConfirmationCode()).startsWith("MB-");
                });
    }

    @Test
    @Order(8)
    @DisplayName("POST /api/v1/appointments — booking with a pre-acquired hold succeeds")
    void book_withHoldId_succeeds() throws Exception {
        LocalDateTime slot = LocalDateTime.now().plusDays(8).withMinute(0).withSecond(0).withNano(0);
        String holdId = holdService.holdSlot(doctorEntityId, slot);

        AppointmentRequest req = buildReq(slot);
        req.setHoldId(holdId);

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @Order(9)
    @DisplayName("POST /api/v1/appointments — same slot returns 409 SLOT_TAKEN")
    void book_sameSlot_returns409() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Book test must succeed first");
        LocalDateTime slot = appointmentRepository.findById(appointmentId).orElseThrow().getScheduledAt();

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildReq(slot))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SLOT_TAKEN"));
    }


    @Test
    @Order(10)
    @DisplayName("GET /api/v1/me/appointments/{id} — returns full appointment detail")
    void getMyAppointmentDetail_returns200() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        mockMvc.perform(get("/api/v1/me/appointments/" + appointmentId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(appointmentId))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.doctorName").isNotEmpty())
                .andExpect(jsonPath("$.data.departmentName").value("General Medicine"));
    }

    @Test
    @Order(11)
    @DisplayName("GET /api/v1/me/appointments?tab=upcoming — returns patient's upcoming appointments")
    void myAppointments_upcomingTab_returnsPage() throws Exception {
        mockMvc.perform(get("/api/v1/me/appointments")
                        .param("tab", "upcoming")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(12)
    @DisplayName("GET /api/v1/me/appointments?tab=past — returns empty page for new patient")
    void myAppointments_pastTab_returnsPage() throws Exception {
        mockMvc.perform(get("/api/v1/me/appointments")
                        .param("tab", "past")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @Order(13)
    @DisplayName("GET /api/v1/me/appointments — unauthenticated returns 401")
    void myAppointments_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/appointments"))
                .andExpect(status().isUnauthorized());
    }


    @Test
    @Order(14)
    @DisplayName("GET /api/v1/policies/cancellation — returns policy with default 24-hour notice")
    void getCancellationPolicy_returns200WithDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/policies/cancellation")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noticeHours").value(24))
                .andExpect(jsonPath("$.data.feeApplies").value(true));
    }


    @Test
    @Order(15)
    @DisplayName("POST /api/v1/appointments/{id}/transition — patient role returns 403")
    void transition_patientRole_returns403() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        TransitionRequest req = new TransitionRequest();
        req.setTo(AppointmentStatus.CONFIRMED);
        mockMvc.perform(post("/api/v1/appointments/" + appointmentId + "/transition")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(16)
    @DisplayName("POST /api/v1/appointments/{id}/transition — PENDING→COMPLETED throws 400 INVALID_TRANSITION")
    void transition_pendingToCompleted_returns400() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        TransitionRequest req = new TransitionRequest();
        req.setTo(AppointmentStatus.COMPLETED);
        mockMvc.perform(post("/api/v1/appointments/" + appointmentId + "/transition")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TRANSITION"));
    }

    @Test
    @Order(17)
    @DisplayName("POST /api/v1/appointments/{id}/transition — PENDING→CANCELLED succeeds")
    void transition_pendingToCancelled_succeeds() throws Exception {
        LocalDateTime slot = LocalDateTime.now().plusDays(14).withMinute(0).withSecond(0).withNano(0);
        MvcResult bookResult = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildReq(slot))))
                .andExpect(status().isCreated())
                .andReturn();
        Long newId = objectMapper.readTree(bookResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        TransitionRequest req = new TransitionRequest();
        req.setTo(AppointmentStatus.CANCELLED);
        req.setReason("Test cancellation");
        mockMvc.perform(post("/api/v1/appointments/" + newId + "/transition")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @Order(18)
    @DisplayName("POST /api/v1/appointments/{id}/transition — missing 'to' field returns 422")
    void transition_missingTo_returns422() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        mockMvc.perform(post("/api/v1/appointments/" + appointmentId + "/transition")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }


    @Test
    @Order(19)
    @DisplayName("POST /api/v1/appointments/{id}/cancel — unauthenticated returns 401")
    void cancel_unauthenticated_returns401() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        mockMvc.perform(post("/api/v1/appointments/" + appointmentId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(20)
    @DisplayName("POST /api/v1/appointments/{id}/cancel — unrelated patient returns 403 ACCESS_DENIED")
    void cancel_unrelatedPatient_returns403() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        String unrelatedToken = loginAndGetToken("it-unrelated@test.com", "Password1!");
        CancelRequest req = new CancelRequest();
        req.setReason("Not my appointment");
        mockMvc.perform(post("/api/v1/appointments/" + appointmentId + "/cancel")
                        .header("Authorization", "Bearer " + unrelatedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @Order(21)
    @DisplayName("POST /api/v1/appointments/{id}/cancel — within 24h notice returns 422 WITHIN_NOTICE_PERIOD")
    void cancel_withinNoticePeriod_returns422() throws Exception {
        LocalDateTime futureSlot = LocalDateTime.now().plusDays(21).withMinute(0).withSecond(0).withNano(0);
        MvcResult bookResult = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildReq(futureSlot))))
                .andExpect(status().isCreated())
                .andReturn();
        Long noticeId = objectMapper.readTree(bookResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        appointmentRepository.findById(noticeId).ifPresent(a -> {
            a.setScheduledAt(LocalDateTime.now().plusHours(12));
            a.setEndTime(LocalDateTime.now().plusHours(12).plusMinutes(30));
            appointmentRepository.save(a);
        });

        CancelRequest req = new CancelRequest();
        req.setReason("Last minute change");
        mockMvc.perform(post("/api/v1/appointments/" + noticeId + "/cancel")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("WITHIN_NOTICE_PERIOD"));
    }

    @Test
    @Order(22)
    @DisplayName("POST /api/v1/appointments/{id}/cancel — patient cancels their own appointment → 200 CANCELLED")
    void cancel_byPatient_succeeds() throws Exception {
        LocalDateTime slot = LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0);
        MvcResult bookResult = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildReq(slot))))
                .andExpect(status().isCreated())
                .andReturn();
        Long cancelId = objectMapper.readTree(bookResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        CancelRequest req = new CancelRequest();
        req.setReason("Changed plans");
        mockMvc.perform(post("/api/v1/appointments/" + cancelId + "/cancel")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(appointmentRepository.findById(cancelId))
                .hasValueSatisfying(a -> {
                    assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
                    assertThat(a.getCancelledAt()).isNotNull();
                    assertThat(a.getCancelledBy()).isNotNull();
                    assertThat(a.getCancellationReason()).isEqualTo("Changed plans");
                });
    }


    @Test
    @Order(23)
    @DisplayName("POST /api/v1/appointments/{id}/reschedule — moves appointment to new slot")
    void reschedule_success_updatesSlot() throws Exception {
        LocalDateTime originalSlot = LocalDateTime.now().plusDays(35).withMinute(0).withSecond(0).withNano(0);
        MvcResult bookResult = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildReq(originalSlot))))
                .andExpect(status().isCreated())
                .andReturn();
        rescheduleApptId = objectMapper.readTree(bookResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        LocalDateTime newStart = LocalDateTime.now().plusDays(40).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime newEnd   = newStart.plusMinutes(30);
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(newStart);
        req.setNewEnd(newEnd);

        mockMvc.perform(post("/api/v1/appointments/" + rescheduleApptId + "/reschedule")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(rescheduleApptId));

        assertThat(appointmentRepository.findById(rescheduleApptId))
                .hasValueSatisfying(a -> assertThat(a.getScheduledAt()).isEqualTo(newStart));
    }

    @Test
    @Order(24)
    @DisplayName("POST /api/v1/appointments/{id}/reschedule — doctor role returns 403")
    void reschedule_doctorRole_returns403() throws Exception {
        Assumptions.assumeTrue(rescheduleApptId != null);
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(LocalDateTime.now().plusDays(50));
        req.setNewEnd(LocalDateTime.now().plusDays(50).plusMinutes(30));
        mockMvc.perform(post("/api/v1/appointments/" + rescheduleApptId + "/reschedule")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(25)
    @DisplayName("POST /api/v1/appointments/{id}/reschedule — missing newEnd returns 422")
    void reschedule_missingNewEnd_returns422() throws Exception {
        Assumptions.assumeTrue(rescheduleApptId != null);
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(LocalDateTime.now().plusDays(50));
        mockMvc.perform(post("/api/v1/appointments/" + rescheduleApptId + "/reschedule")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("newEnd"));
    }


    @Test
    @Order(26)
    @DisplayName("POST /api/v1/appointments/{id}/calendar.ics — returns text/calendar with VCALENDAR body")
    void getCalendarIcs_returnsValidIcsBlob() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        MvcResult result = mockMvc.perform(
                        post("/api/v1/appointments/" + appointmentId + "/calendar.ics")
                                .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar"))
                .andReturn();

        String ics = result.getResponse().getContentAsString();
        assertThat(ics).contains("BEGIN:VCALENDAR", "BEGIN:VEVENT", "END:VEVENT", "END:VCALENDAR");
        assertThat(ics).contains("@medibook.com");
    }


    @Test
    @Order(27)
    @DisplayName("GET /api/v1/appointments/{id} (doctor) — returns appointment detail")
    void doctorGetAppointmentDetail_returns200() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        mockMvc.perform(get("/api/v1/appointments/" + appointmentId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(appointmentId));
    }

    @Test
    @Order(28)
    @DisplayName("GET /api/v1/appointments/{id} (doctor) — patient role returns 403")
    void doctorGetAppointmentDetail_patientRole_returns403() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        mockMvc.perform(get("/api/v1/appointments/" + appointmentId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(29)
    @DisplayName("GET /api/v1/patients/{id}/summary — returns patient summary response")
    void getPatientSummary_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/patients/1/summary")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(30)
    @DisplayName("GET /api/v1/patients/{id}/summary — patient role returns 403")
    void getPatientSummary_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/patients/1/summary")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }


    @Test
    @Order(31)
    @DisplayName("GET /api/v1/me/schedule — doctor role returns 200")
    void getDailySchedule_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule")
                        .param("date", LocalDate.now().plusDays(7).toString())
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(32)
    @DisplayName("GET /api/v1/me/schedule — patient role returns 403")
    void getDailySchedule_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule")
                        .param("date", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(33)
    @DisplayName("GET /api/v1/me/schedule/week — returns weekly summary map")
    void getWeeklySummary_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/week")
                        .param("weekOf", LocalDate.now().toString())
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(34)
    @DisplayName("GET /api/v1/me/schedule/summary — returns today's schedule summary")
    void getScheduleSummary_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/summary")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(35)
    @DisplayName("GET /api/v1/me/schedule/up-next — returns next CONFIRMED appointment or null")
    void getUpNext_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/up-next")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk());
    }


    @Test
    @Order(36)
    @DisplayName("POST /api/v1/appointments/{id}/call — unauthenticated returns 401")
    void callPatient_unauthenticated_returns401() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");
        mockMvc.perform(post("/api/v1/appointments/" + appointmentId + "/call"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(37)
    @DisplayName("POST /api/v1/appointments/{id}/call — patient role returns 403")
    void callPatient_patientRole_returns403() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");
        mockMvc.perform(post("/api/v1/appointments/" + appointmentId + "/call")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(38)
    @DisplayName("POST /api/v1/appointments/{id}/call — non-existent appointment returns 404")
    void callPatient_appointmentNotFound_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/appointments/999999/call")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(39)
    @DisplayName("POST /api/v1/appointments/{id}/call — doctor returns 200 with tel: URI")
    void callPatient_doctorRole_returnsTelUri() throws Exception {
        Assumptions.assumeTrue(appointmentId != null, "Booking must succeed first");
        mockMvc.perform(post("/api/v1/appointments/" + appointmentId + "/call")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(
                        org.hamcrest.Matchers.startsWith("tel:")));
    }
}
