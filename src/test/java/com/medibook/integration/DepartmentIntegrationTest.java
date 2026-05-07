package com.medibook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.department.dto.DepartmentRequest;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Department Integration Tests")
class DepartmentIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.2")
            .withDatabaseName("medibook_dept_it")
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

    @Autowired MockMvc              mockMvc;
    @Autowired ObjectMapper         objectMapper;
    @Autowired UserRepository       userRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired PasswordEncoder      passwordEncoder;

    String patientToken;
    String adminToken;
    Long   seedDeptId;        // pre-existing department
    Long   createdDeptId;     // created in @Order(8)

    @BeforeAll
    void setUp() throws Exception {
        userRepository.save(User.builder()
                .email("dept-patient@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Alice").lastName("P").role(Role.ROLE_PATIENT).build());
        userRepository.save(User.builder()
                .email("dept-admin@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Carol").lastName("A").role(Role.ROLE_ADMIN).build());

        Department seed = departmentRepository.save(
                Department.builder().name("Seed Department").code("SEED").build());
        seedDeptId = seed.getId();

        patientToken = loginAndGetToken("dept-patient@test.com", "Password1!");
        adminToken   = loginAndGetToken("dept-admin@test.com",   "Password1!");
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

    // ─── GET /api/v1/departments (public read) ────────────────────────────────

    @Test @Order(1)
    @DisplayName("GET /api/v1/departments — unauthenticated returns 401")
    void getAll_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/departments")).andExpect(status().isUnauthorized());
    }

    @Test @Order(2)
    @DisplayName("GET /api/v1/departments — returns list of active departments only")
    void getAll_authenticated_returnsActiveDepartments() throws Exception {
        mockMvc.perform(get("/api/v1/departments")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").exists());
    }

    @Test @Order(3)
    @DisplayName("GET /api/v1/departments/{id} — existing returns department")
    void getById_existing_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/departments/" + seedDeptId)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Seed Department"))
                .andExpect(jsonPath("$.data.code").value("SEED"));
    }

    @Test @Order(4)
    @DisplayName("GET /api/v1/departments/{id} — not found returns 404")
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/departments/999999")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isNotFound());
    }

    // ─── GET /api/v1/admin/departments ───────────────────────────────────────

    @Test @Order(5)
    @DisplayName("GET /api/v1/admin/departments — patient role returns 403")
    void adminGetStats_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/departments")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    @Test @Order(6)
    @DisplayName("GET /api/v1/admin/departments — admin returns paginated stats")
    void adminGetStats_adminRole_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/departments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test @Order(7)
    @DisplayName("GET /api/v1/admin/departments?q=Seed — filters by query string")
    void adminGetStats_withQueryFilter_returnsFiltered() throws Exception {
        mockMvc.perform(get("/api/v1/admin/departments")
                        .param("q", "Seed")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ─── POST /api/v1/admin/departments ──────────────────────────────────────

    @Test @Order(8)
    @DisplayName("POST /api/v1/admin/departments — patient role returns 403")
    void create_patientRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/departments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("Blocked", "BLK", null))))
                .andExpect(status().isForbidden());
    }

    @Test @Order(9)
    @DisplayName("POST /api/v1/admin/departments — missing name returns 422")
    void create_missingName_returns422() throws Exception {
        DepartmentRequest req = new DepartmentRequest();
        req.setCode("CODE");
        mockMvc.perform(post("/api/v1/admin/departments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test @Order(10)
    @DisplayName("POST /api/v1/admin/departments — admin creates department → 201 with uppercased code")
    void create_adminSuccess_returns201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/departments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest("New Radiology", "rad01", "Imaging dept"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("New Radiology"))
                .andExpect(jsonPath("$.data.code").value("RAD01"))  // uppercased
                .andReturn();

        createdDeptId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
        assertThat(departmentRepository.findById(createdDeptId)).isPresent();
    }

    @Test @Order(11)
    @DisplayName("POST /api/v1/admin/departments — duplicate name returns 409 NAME_EXISTS")
    void create_duplicateName_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/admin/departments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest("New Radiology", "RAD99", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NAME_EXISTS"));
    }

    // ─── PATCH /api/v1/admin/departments/{id} ────────────────────────────────

    @Test @Order(12)
    @DisplayName("PATCH /api/v1/admin/departments/{id} — admin updates fields → 200")
    void update_adminSuccess_returns200() throws Exception {
        Assumptions.assumeTrue(createdDeptId != null);
        mockMvc.perform(patch("/api/v1/admin/departments/" + createdDeptId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildRequest("Updated Radiology", "RADUPD", "Updated desc"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated Radiology"))
                .andExpect(jsonPath("$.data.code").value("RADUPD"));
    }

    // ─── POST /api/v1/admin/departments/{id}/deactivate ───────────────────────

    @Test @Order(13)
    @DisplayName("POST /api/v1/admin/departments/{id}/deactivate — sets department inactive")
    void deactivate_adminSuccess_returns200() throws Exception {
        Assumptions.assumeTrue(createdDeptId != null);
        mockMvc.perform(post("/api/v1/admin/departments/" + createdDeptId + "/deactivate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(departmentRepository.findById(createdDeptId))
                .hasValueSatisfying(d -> assertThat(d.isActive()).isFalse());
    }

    @Test @Order(14)
    @DisplayName("GET /api/v1/departments — deactivated department no longer appears in active list")
    void getAllActive_afterDeactivate_doesNotIncludeDeactivated() throws Exception {
        Assumptions.assumeTrue(createdDeptId != null);
        mockMvc.perform(get("/api/v1/departments")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + createdDeptId + ")]").doesNotExist());
    }

    // ─── POST /api/v1/admin/departments/{id}/reactivate ───────────────────────

    @Test @Order(15)
    @DisplayName("POST /api/v1/admin/departments/{id}/reactivate — restores active status")
    void reactivate_adminSuccess_returns200() throws Exception {
        Assumptions.assumeTrue(createdDeptId != null);
        mockMvc.perform(post("/api/v1/admin/departments/" + createdDeptId + "/reactivate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(departmentRepository.findById(createdDeptId))
                .hasValueSatisfying(d -> assertThat(d.isActive()).isTrue());
    }

    // ─── GET /api/v1/admin/departments/export.csv ────────────────────────────

    @Test @Order(16)
    @DisplayName("GET /api/v1/admin/departments/export.csv — returns text/csv with CSV headers")
    void exportCsv_adminSuccess_returnsValidCsv() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/departments/export.csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn();

        String csv = result.getResponse().getContentAsString();
        assertThat(csv).startsWith("ID,Name,Code,Doctors Count,Appt Count 90d,Status");
        assertThat(csv).contains("SEED");
    }

    @Test @Order(17)
    @DisplayName("GET /api/v1/admin/departments/export.csv — patient role returns 403")
    void exportCsv_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/departments/export.csv")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private DepartmentRequest buildRequest(String name, String code, String description) {
        DepartmentRequest req = new DepartmentRequest();
        req.setName(name);
        req.setCode(code);
        req.setDescription(description);
        return req;
    }
}
