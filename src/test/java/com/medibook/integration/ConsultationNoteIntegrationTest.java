package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.consultation.dto.ConsultationNoteRequest;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ConsultationNoteController.
 *
 * Setup: Patient books an appointment with a doctor, then the doctor creates
 * consultation notes. PHI fields (diagnosis, treatmentPlan) are encrypted at
 * rest and decrypted transparently via PhiAttributeConverter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ConsultationNote Integration Tests")
class ConsultationNoteIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.2")
            .withDatabaseName("medibook_notes_it")
            .withUsername("test").withPassword("test");

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

    @Autowired MockMvc                   mockMvc;
    @Autowired ObjectMapper              objectMapper;
    @Autowired UserRepository            userRepository;
    @Autowired DepartmentRepository      departmentRepository;
    @Autowired DoctorRepository          doctorRepository;
    @Autowired ConsultationNoteRepository noteRepository;
    @Autowired PasswordEncoder           passwordEncoder;

    String patientToken;
    String doctorToken;
    Long   appointmentId;
    Long   noteId;
    Long   doctorEntityId;

    @BeforeAll
    void setUpFixtures() throws Exception {
        Department dept = departmentRepository.save(
                Department.builder().name("Notes-Cardiology").code("NTCD").build());

        User patientUser = userRepository.save(User.builder()
                .email("notes-patient@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Alice").lastName("Notes").role(Role.ROLE_PATIENT).build());

        User docUser = userRepository.save(User.builder()
                .email("notes-doctor@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Bob").lastName("Notes").role(Role.ROLE_DOCTOR).build());

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .user(docUser).department(dept).licenseNumber("LIC-NOTES-001")
                .specialization("Cardiology").build());
        doctorEntityId = doctor.getId();

        patientToken = loginAndGetToken("notes-patient@test.com", "Password1!");
        doctorToken  = loginAndGetToken("notes-doctor@test.com",  "Password1!");

        AppointmentRequest apptReq = new AppointmentRequest();
        apptReq.setDoctorId(doctorEntityId);
        apptReq.setScheduledAt(LocalDateTime.now().plusDays(5).withMinute(0).withSecond(0).withNano(0));
        apptReq.setType(AppointmentType.IN_PERSON);
        apptReq.setDurationMins(30);

        MvcResult bookResult = mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(apptReq)))
                .andExpect(status().isCreated()).andReturn();

        appointmentId = objectMapper.readTree(bookResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();
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

    private ConsultationNoteRequest buildNoteRequest(String diagnosis, String treatmentPlan) {
        ConsultationNoteRequest req = new ConsultationNoteRequest();
        req.setDiagnosis(diagnosis);
        req.setTreatmentPlan(treatmentPlan);
        req.setPrescriptions("Aspirin 100mg");
        req.setFollowUpDate(LocalDate.now().plusMonths(1));
        return req;
    }


    @Test @Order(1)
    @DisplayName("POST .../appointment/{id} — patient role returns 403")
    void create_patientRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/consultation-notes/appointment/" + appointmentId)
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildNoteRequest("dx", "tx"))))
                .andExpect(status().isForbidden());
    }

    @Test @Order(2)
    @DisplayName("POST .../appointment/{id} — missing diagnosis returns 422")
    void create_missingDiagnosis_returns422() throws Exception {
        ConsultationNoteRequest req = new ConsultationNoteRequest();
        req.setTreatmentPlan("Some plan");
        mockMvc.perform(post("/api/v1/consultation-notes/appointment/" + appointmentId)
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("diagnosis"));
    }

    @Test @Order(3)
    @DisplayName("POST .../appointment/{id} — doctor creates note → 201, PHI fields stored & decrypted on read")
    void create_doctorSuccess_returns201WithPhiDecrypted() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/consultation-notes/appointment/" + appointmentId)
                                .header("Authorization", "Bearer " + doctorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        buildNoteRequest("Hypertension Stage 2", "Amlodipine 5mg"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.diagnosis").value("Hypertension Stage 2"))
                .andExpect(jsonPath("$.data.treatmentPlan").value("Amlodipine 5mg"))
                .andExpect(jsonPath("$.data.patientName").value("Alice Notes"))
                .andExpect(jsonPath("$.data.doctorName").value("Bob Notes"))
                .andExpect(jsonPath("$.data.followUpDate").isNotEmpty())
                .andReturn();

        noteId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        var rawNote = noteRepository.findById(noteId).orElseThrow();
        assertThat(rawNote.getDiagnosis()).isNotEqualTo("Hypertension Stage 2"); // encrypted in DB
    }

    @Test @Order(4)
    @DisplayName("POST .../appointment/{id} — duplicate note returns 409 NOTE_EXISTS")
    void create_duplicate_returns409() throws Exception {
        Assumptions.assumeTrue(appointmentId != null);
        mockMvc.perform(post("/api/v1/consultation-notes/appointment/" + appointmentId)
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildNoteRequest("Second dx", "Second tx"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOTE_EXISTS"));
    }

    @Test @Order(5)
    @DisplayName("POST .../appointment/{id} — appointment not found returns 404")
    void create_appointmentNotFound_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/consultation-notes/appointment/99999")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildNoteRequest("dx", "tx"))))
                .andExpect(status().isNotFound());
    }


    @Test @Order(6)
    @DisplayName("GET .../appointment/{id} — patient role returns 403")
    void getByAppointment_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/consultation-notes/appointment/" + appointmentId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    @Test @Order(7)
    @DisplayName("GET .../appointment/{id} — doctor returns 200 with decrypted PHI")
    void getByAppointment_doctor_returns200WithDecryptedPhi() throws Exception {
        Assumptions.assumeTrue(noteId != null);
        mockMvc.perform(get("/api/v1/consultation-notes/appointment/" + appointmentId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(noteId))
                .andExpect(jsonPath("$.data.diagnosis").value("Hypertension Stage 2"))
                .andExpect(jsonPath("$.data.treatmentPlan").value("Amlodipine 5mg"));
    }

    @Test @Order(8)
    @DisplayName("GET .../appointment/{id} — no note returns 404")
    void getByAppointment_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/consultation-notes/appointment/99999")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isNotFound());
    }


    @Test @Order(9)
    @DisplayName("GET /api/v1/consultation-notes/my-history — unauthenticated returns 401")
    void getMyHistory_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/consultation-notes/my-history"))
                .andExpect(status().isUnauthorized());
    }

    @Test @Order(10)
    @DisplayName("GET /api/v1/consultation-notes/my-history — patient sees own consultation history")
    void getMyHistory_patient_returnsHistory() throws Exception {
        mockMvc.perform(get("/api/v1/consultation-notes/my-history")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].diagnosis").value("Hypertension Stage 2"));
    }


    @Test @Order(11)
    @DisplayName("PUT /api/v1/consultation-notes/{id} — patient role returns 403")
    void update_patientRole_returns403() throws Exception {
        Assumptions.assumeTrue(noteId != null);
        mockMvc.perform(put("/api/v1/consultation-notes/" + noteId)
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildNoteRequest("new dx", "new tx"))))
                .andExpect(status().isForbidden());
    }

    @Test @Order(12)
    @DisplayName("PUT /api/v1/consultation-notes/{id} — doctor updates note → 200, PHI fields updated")
    void update_doctor_returns200WithUpdatedFields() throws Exception {
        Assumptions.assumeTrue(noteId != null);
        ConsultationNoteRequest req = buildNoteRequest("Updated Hypertension", "Amlodipine 10mg");
        req.setFollowUpDate(LocalDate.now().plusMonths(3));

        mockMvc.perform(put("/api/v1/consultation-notes/" + noteId)
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diagnosis").value("Updated Hypertension"))
                .andExpect(jsonPath("$.data.treatmentPlan").value("Amlodipine 10mg"));
    }

    @Test @Order(13)
    @DisplayName("PUT /api/v1/consultation-notes/{id} — missing treatmentPlan returns 422")
    void update_missingTreatmentPlan_returns422() throws Exception {
        Assumptions.assumeTrue(noteId != null);
        ConsultationNoteRequest req = new ConsultationNoteRequest();
        req.setDiagnosis("Valid diagnosis");

        mockMvc.perform(put("/api/v1/consultation-notes/" + noteId)
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("treatmentPlan"));
    }
}
