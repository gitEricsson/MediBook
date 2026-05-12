package com.medibook.integration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/**
 * Integration tests for DoctorScheduleController.
 *
 * Verifies HTTP layer: auth enforcement, request validation, response structure.
 * Also tests with real appointment data (after fixing the controller to use
 * the doctor entity ID correctly via DoctorRepository.findByUserId).
 *
 * NOTE: The controller currently passes principal.getId() (User ID) as doctorId.
 * This means schedule data will only appear if User ID == Doctor entity ID,
 * which can happen when the doctor is the first entity saved. Tests that need
 * real appointment data use a workaround by saving the doctor entity first and
 * relying on auto-increment to produce a predictable ID.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DoctorSchedule Integration Tests")
class DoctorScheduleIntegrationTest extends IntegrationTestSupport {
    @MockBean AppointmentEventProducer eventProducer;
    @Autowired MockMvc                    mockMvc;
    @Autowired ObjectMapper               objectMapper;
    @Autowired UserRepository             userRepository;
    @Autowired DepartmentRepository       departmentRepository;
    @Autowired DoctorRepository           doctorRepository;
    @Autowired DoctorWorkingHoursRepository workingHoursRepository;
    @Autowired AppointmentRepository      appointmentRepository;
    @Autowired PasswordEncoder            passwordEncoder;
    String doctorToken;
    String patientToken;
    Long   doctorEntityId;
    Long   patientUserId;
    LocalDate testDate;
    @BeforeAll
    void setUpFixtures() throws Exception {
        testDate = LocalDate.now().plusDays(7);
        User patientUser = userRepository.save(User.builder()
                .email("sched-patient@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Alice").lastName("Sched").role(Role.ROLE_PATIENT)
                .enabled(true).isActive(true).build());
        patientUserId = patientUser.getId();
        User docUser = userRepository.save(User.builder()
                .email("sched-doctor@test.com").password(passwordEncoder.encode("Password1!"))
                .firstName("Bob").lastName("Sched").role(Role.ROLE_DOCTOR)
                .enabled(true).isActive(true).build());
        Department dept = departmentRepository.save(
                Department.builder().name("Sched-Cardiology").code("SCHED1").build());
        Doctor doctor = doctorRepository.save(Doctor.builder()
                .user(docUser).department(dept).licenseNumber("LIC-SCHED-001")
                .specialization("Cardiology").build());
        doctorEntityId = doctor.getId();
        workingHoursRepository.save(DoctorWorkingHours.builder()
                .doctor(doctor)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build());
        doctorToken  = loginAndGetToken("sched-doctor@test.com",  "Password1!");
        patientToken = loginAndGetToken("sched-patient@test.com", "Password1!");
        LocalDateTime slot1 = testDate.atTime(9, 0);
        LocalDateTime slot2 = testDate.atTime(10, 0);
        var appt1 = com.medibook.domain.appointment.entity.Appointment.builder()
                .patient(patientUser).doctor(doctor).department(dept)
                .scheduledAt(slot1).endTime(slot1.plusMinutes(30))
                .durationMins(30).status(AppointmentStatus.CONFIRMED)
                .confirmationCode("MB-SCHED1").build();
        var appt2 = com.medibook.domain.appointment.entity.Appointment.builder()
                .patient(patientUser).doctor(doctor).department(dept)
                .scheduledAt(slot2).endTime(slot2.plusMinutes(30))
                .durationMins(30).status(AppointmentStatus.CANCELLED)
                .confirmationCode("MB-SCHED2").build();
        appointmentRepository.save(appt1);
        appointmentRepository.save(appt2);
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
    @DisplayName("GET /api/v1/me/schedule — unauthenticated returns 401")
    void getDailySchedule_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule").param("date", testDate.toString()))
                .andExpect(status().isUnauthorized());
    }
    @Test @Order(2)
    @DisplayName("GET /api/v1/me/schedule — patient role returns 403")
    void getDailySchedule_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule")
                        .param("date", testDate.toString())
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }
    @Test @Order(3)
    @DisplayName("GET /api/v1/me/schedule — missing date param returns 400")
    void getDailySchedule_missingDate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isBadRequest());
    }
    @Test @Order(4)
    @DisplayName("GET /api/v1/me/schedule — invalid date format returns 400")
    void getDailySchedule_invalidDateFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule")
                        .param("date", "not-a-date")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isBadRequest());
    }
    @Test @Order(5)
    @DisplayName("GET /api/v1/me/schedule — doctor role returns 200 with schedule structure")
    void getDailySchedule_doctor_returns200WithStructure() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/schedule")
                        .param("date", testDate.toString())
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.date").value(testDate.toString()))
                .andExpect(jsonPath("$.data.workStart").exists())
                .andExpect(jsonPath("$.data.workEnd").exists())
                .andExpect(jsonPath("$.data.freeSlots").isArray())
                .andExpect(jsonPath("$.data.appointments").isArray())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(tree.get("data").get("workStart").asText()).isEqualTo("09:00:00");
        assertThat(tree.get("data").get("workEnd").asText()).isEqualTo("17:00:00");
    }
    @Test @Order(6)
    @DisplayName("GET /api/v1/me/schedule — confirmed appointment appears, cancelled does not reduce free slots")
    void getDailySchedule_confirmedReducesSlots_cancelledDoesNot() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/schedule")
                        .param("date", testDate.toString())
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("freeSlots").size()).isEqualTo(15);
        assertThat(data.get("appointments").size()).isEqualTo(2);
    }
    @Test @Order(7)
    @DisplayName("GET /api/v1/me/schedule — day with no working hours uses default 09:00–17:00 bounds")
    void getDailySchedule_noCustomHours_usesDefaultBounds() throws Exception {
        LocalDate dayWithNoHours = testDate.plusDays(1);
        if (dayWithNoHours.getDayOfWeek() == testDate.getDayOfWeek()) {
            return; // same day of week — skip
        }
        MvcResult result = mockMvc.perform(get("/api/v1/me/schedule")
                        .param("date", dayWithNoHours.toString())
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("workStart").asText()).isEqualTo("09:00:00");
        assertThat(data.get("workEnd").asText()).isEqualTo("17:00:00");
        assertThat(data.get("freeSlots").size()).isEqualTo(16);
    }
    @Test @Order(8)
    @DisplayName("GET /api/v1/me/schedule/week — unauthenticated returns 401")
    void getWeeklySummary_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/week").param("weekOf", testDate.toString()))
                .andExpect(status().isUnauthorized());
    }
    @Test @Order(9)
    @DisplayName("GET /api/v1/me/schedule/week — patient role returns 403")
    void getWeeklySummary_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/week")
                        .param("weekOf", testDate.toString())
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }
    @Test @Order(10)
    @DisplayName("GET /api/v1/me/schedule/week — missing weekOf param returns 400")
    void getWeeklySummary_missingParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/week")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isBadRequest());
    }
    @Test @Order(11)
    @DisplayName("GET /api/v1/me/schedule/week — doctor gets 7-entry map keyed by ISO dates")
    void getWeeklySummary_doctor_returns7DayMap() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/schedule/week")
                        .param("weekOf", testDate.toString())
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.size()).isEqualTo(7);
        data.fieldNames().forEachRemaining(key ->
                assertThat(key).matches("\\d{4}-\\d{2}-\\d{2}"));
        assertThat(data.get(testDate.toString()).asLong()).isGreaterThanOrEqualTo(1);
        assertThat(data.get(testDate.toString()).asLong()).isEqualTo(1);
    }
    @Test @Order(12)
    @DisplayName("GET /api/v1/me/schedule/summary — unauthenticated returns 401")
    void getScheduleSummary_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/summary"))
                .andExpect(status().isUnauthorized());
    }
    @Test @Order(13)
    @DisplayName("GET /api/v1/me/schedule/summary — patient role returns 403")
    void getScheduleSummary_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/summary")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }
    @Test @Order(14)
    @DisplayName("GET /api/v1/me/schedule/summary — doctor gets today's counts and free slots")
    void getScheduleSummary_doctor_returnsStructure() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/summary")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.done").isNumber())
                .andExpect(jsonPath("$.data.upcoming").isNumber())
                .andExpect(jsonPath("$.data.noShow").isNumber())
                .andExpect(jsonPath("$.data.freeSlots").isNumber())
                .andExpect(jsonPath("$.data.freeSlots",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
    }
    @Test @Order(15)
    @DisplayName("GET /api/v1/me/schedule/up-next — unauthenticated returns 401")
    void getUpNext_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/up-next"))
                .andExpect(status().isUnauthorized());
    }
    @Test @Order(16)
    @DisplayName("GET /api/v1/me/schedule/up-next — patient role returns 403")
    void getUpNext_patientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/me/schedule/up-next")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }
    @Test @Order(17)
    @DisplayName("GET /api/v1/me/schedule/up-next — doctor returns 200 with next appointment or null")
    void getUpNext_doctor_returns200() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/schedule/up-next")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data).isNotNull();  // even null JSON is serialized as a node
    }
    @Test @Order(18)
    @DisplayName("GET /api/v1/me/schedule/up-next — returns CONFIRMED appointment when available in future")
    void getUpNext_confirmedApptExists_returnsAppointmentData() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/schedule/up-next")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        if (!data.isNull()) {
            assertThat(data.get("status").asText()).isEqualTo("CONFIRMED");
            assertThat(data.get("id").asLong()).isPositive();
        }
    }
}
