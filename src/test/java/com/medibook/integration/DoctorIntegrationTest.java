package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.dto.DoctorRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Doctor Integration Tests")
class DoctorIntegrationTest {


    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://medibook-mysql:3306/medibook_db");
        registry.add("spring.datasource.username", () -> "medibook");
        registry.add("spring.datasource.password", () -> "medibook");
        registry.add("spring.data.redis.host", () -> "medibook-redis");
        registry.add("spring.data.redis.port", () -> 6379);
        registry.add("spring.data.cassandra.contact-points", () -> "medibook-cassandra");
        registry.add("spring.data.cassandra.local-datacenter", () -> "datacenter1");
        registry.add("spring.kafka.bootstrap-servers", () -> "kafka:9092");
    }

    @MockBean AppointmentEventProducer eventProducer;

    @Autowired MockMvc             mockMvc;
    @Autowired ObjectMapper        objectMapper;
    @Autowired UserRepository      userRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired DoctorRepository    doctorRepository;
    @Autowired PasswordEncoder     passwordEncoder;

    String patientToken;
    String adminToken;
    String doctorToken;
    Long   departmentId;
    Long   doctorEntityId;
    Long   newDoctorUserId;    // user to register as doctor in create test

    @BeforeAll
    void setUp() throws Exception {
        User patient = userRepository.save(User.builder()
                .email("dr-test-patient@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Alice").lastName("Patient").role(Role.ROLE_PATIENT).build());

        User admin = userRepository.save(User.builder()
                .email("dr-test-admin@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Carol").lastName("Admin").role(Role.ROLE_ADMIN).build());

        User docUser = userRepository.save(User.builder()
                .email("dr-test-doctor@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Bob").lastName("Doctor").role(Role.ROLE_DOCTOR).build());

        User newDocUser = userRepository.save(User.builder()
                .email("dr-test-newdoc@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("New").lastName("Doctor").role(Role.ROLE_DOCTOR).build());
        newDoctorUserId = newDocUser.getId();

        Department dept = departmentRepository.save(
                Department.builder().name("IT-Cardiology").code("ITC1").build());
        departmentId = dept.getId();

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .user(docUser).department(dept).licenseNumber("LIC-DR-IT-001")
                .specialization("Cardiology").build());
        doctorEntityId = doctor.getId();

        patientToken = loginAndGetToken("dr-test-patient@test.com", "Password1!");
        adminToken   = loginAndGetToken("dr-test-admin@test.com",   "Password1!");
        doctorToken  = loginAndGetToken("dr-test-doctor@test.com",  "Password1!");
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
    @DisplayName("GET /api/v1/doctors — unauthenticated returns 401")
    void getAll_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/doctors")).andExpect(status().isUnauthorized());
    }

    @Test @Order(2)
    @DisplayName("GET /api/v1/doctors — authenticated returns paginated list")
    void getAll_authenticated_returnsPage() throws Exception {
        mockMvc.perform(get("/api/v1/doctors")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }


    @Test @Order(3)
    @DisplayName("GET /api/v1/doctors/{id} — existing returns doctor with fullName and department")
    void getById_existing_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/doctors/" + doctorEntityId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(doctorEntityId))
                .andExpect(jsonPath("$.data.fullName").value("Bob Doctor"))
                .andExpect(jsonPath("$.data.departmentName").value("IT-Cardiology"))
                .andExpect(jsonPath("$.data.licenseNumber").value("LIC-DR-IT-001"));
    }

    @Test @Order(4)
    @DisplayName("GET /api/v1/doctors/{id} — non-existent returns 404")
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/doctors/999999")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isNotFound());
    }


    @Test @Order(5)
    @DisplayName("GET /api/v1/doctors/department/{id} — returns doctors in department")
    void getByDepartment_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/doctors/department/" + departmentId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test @Order(6)
    @DisplayName("GET /api/v1/doctors/department/{id} — empty department returns empty page")
    void getByDepartment_unknownDept_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/doctors/department/99999")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }


    @Test @Order(7)
    @DisplayName("POST /api/v1/doctors — patient role returns 403")
    void register_patientRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/doctors")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(newDoctorUserId, departmentId, "LIC-BLOCKED", null))))
                .andExpect(status().isForbidden());
    }

    @Test @Order(8)
    @DisplayName("POST /api/v1/doctors — missing licenseNumber returns 422")
    void register_missingLicense_returns422() throws Exception {
        DoctorRequest req = new DoctorRequest();
        req.setUserId(newDoctorUserId);
        req.setDepartmentId(departmentId);
        mockMvc.perform(post("/api/v1/doctors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("licenseNumber"));
    }

    @Test @Order(9)
    @DisplayName("POST /api/v1/doctors — admin creates doctor → 201 with correct fields")
    void register_adminSuccess_returns201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/doctors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest(newDoctorUserId, departmentId, "LIC-NEW-001", "Neuro"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.licenseNumber").value("LIC-NEW-001"))
                .andExpect(jsonPath("$.data.fullName").value("New Doctor"))
                .andExpect(jsonPath("$.data.departmentName").value("IT-Cardiology"))
                .andReturn();

        Long newDoctorId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
        assertThat(doctorRepository.findById(newDoctorId)).isPresent();
    }

    @Test @Order(10)
    @DisplayName("POST /api/v1/doctors — duplicate licenseNumber returns 409 LICENSE_TAKEN")
    void register_duplicateLicense_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/doctors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest(newDoctorUserId, departmentId, "LIC-NEW-001", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LICENSE_TAKEN"));
    }


    @Test @Order(11)
    @DisplayName("PUT /api/v1/doctors/{id} — patient role returns 403")
    void update_patientRole_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/doctors/" + doctorEntityId)
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest(1L, departmentId, "LIC-DR-IT-001", "Blocked"))))
                .andExpect(status().isForbidden());
    }

    @Test @Order(12)
    @DisplayName("PUT /api/v1/doctors/{id} — admin updates specialization → 200")
    void update_adminSuccess_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/doctors/" + doctorEntityId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest(1L, departmentId, "LIC-DR-IT-001", "Updated Cardiology"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.specialization").value("Updated Cardiology"));
    }

    @Test @Order(13)
    @DisplayName("PUT /api/v1/doctors/{id} — doctor role can update own profile → 200")
    void update_doctorRoleSuccess_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/doctors/" + doctorEntityId)
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest(1L, departmentId, "LIC-DR-IT-001", "Doctor-Updated"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.specialization").value("Doctor-Updated"));
    }


    private DoctorRequest buildRequest(Long userId, Long deptId, String license, String spec) {
        DoctorRequest req = new DoctorRequest();
        req.setUserId(userId);
        req.setDepartmentId(deptId);
        req.setLicenseNumber(license);
        req.setSpecialization(spec);
        return req;
    }
}
