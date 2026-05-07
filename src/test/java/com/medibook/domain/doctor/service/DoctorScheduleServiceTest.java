package com.medibook.domain.doctor.service;

import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.dto.ScheduleDayResponse;
import com.medibook.domain.doctor.dto.ScheduleSummaryResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoctorScheduleService — Unit Tests")
class DoctorScheduleServiceTest {

    @Mock AppointmentRepository        appointmentRepository;
    @Mock DoctorWorkingHoursRepository workingHoursRepository;

    @InjectMocks DoctorScheduleService scheduleService;

    // Fixed date — Sunday (dayOfWeek = 7) so tests are deterministic
    private static final LocalDate   DATE       = LocalDate.of(2026, 6, 21);
    private static final int         DOW        = 7; // Sunday
    private static final Long        DOCTOR_ID  = 10L;

    private Appointment pendingAppt;
    private Appointment confirmedAppt;
    private Appointment cancelledAppt;
    private Appointment noShowAppt;
    private Appointment completedAppt;

    @BeforeEach
    void setUp() {
        User patient = User.builder().id(1L).email("p@test.com")
                .firstName("Alice").lastName("P").role(Role.ROLE_PATIENT).build();
        User docUser = User.builder().id(2L).email("d@test.com")
                .firstName("Bob").lastName("D").role(Role.ROLE_DOCTOR).build();
        Department dept = Department.builder().id(1L).name("Cardiology").build();
        Doctor doctor = Doctor.builder().id(DOCTOR_ID).user(docUser).department(dept)
                .licenseNumber("LIC-001").build();

        // Appointments at different times (09:00, 10:00, 11:00, 12:00, 13:00)
        pendingAppt   = appt(doctor, patient, DATE.atTime(9, 0),  AppointmentStatus.PENDING);
        confirmedAppt = appt(doctor, patient, DATE.atTime(10, 0), AppointmentStatus.CONFIRMED);
        cancelledAppt = appt(doctor, patient, DATE.atTime(11, 0), AppointmentStatus.CANCELLED);
        noShowAppt    = appt(doctor, patient, DATE.atTime(12, 0), AppointmentStatus.NO_SHOW);
        completedAppt = appt(doctor, patient, DATE.atTime(13, 0), AppointmentStatus.COMPLETED);
    }

    // ─── getDailySchedule — working hours ────────────────────────────────────

    @Test
    @DisplayName("getDailySchedule — no working hours configured uses default 09:00–17:00")
    void getDailySchedule_noWorkingHours_usesNineToFiveDefault() {
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any())).thenReturn(List.of());

        ScheduleDayResponse response = scheduleService.getDailySchedule(DOCTOR_ID, DATE);

        assertThat(response.getWorkStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.getWorkEnd()).isEqualTo(LocalTime.of(17, 0));
        // 9:00–17:00 in 30-min slots = 16 free slots
        assertThat(response.getFreeSlots()).hasSize(16);
    }

    @Test
    @DisplayName("getDailySchedule — custom working hours override the default")
    void getDailySchedule_withWorkingHours_usesCustomBounds() {
        DoctorWorkingHours hours = buildHours(LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of(hours));
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any())).thenReturn(List.of());

        ScheduleDayResponse response = scheduleService.getDailySchedule(DOCTOR_ID, DATE);

        assertThat(response.getWorkStart()).isEqualTo(LocalTime.of(8, 0));
        assertThat(response.getWorkEnd()).isEqualTo(LocalTime.of(12, 0));
        // 8:00–12:00 = 8 slots
        assertThat(response.getFreeSlots()).hasSize(8);
    }

    // ─── getDailySchedule — slot availability ─────────────────────────────────

    @Test
    @DisplayName("getDailySchedule — PENDING/CONFIRMED/COMPLETED appointments block their time slots")
    void getDailySchedule_activeAppointments_reduceFreeSlots() {
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        // 3 non-cancelled appointments at 09:00, 10:00, 13:00
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any()))
                .thenReturn(List.of(pendingAppt, confirmedAppt, completedAppt));

        ScheduleDayResponse response = scheduleService.getDailySchedule(DOCTOR_ID, DATE);

        // 16 total − 3 taken = 13 free
        assertThat(response.getFreeSlots()).hasSize(13);
    }

    @Test
    @DisplayName("getDailySchedule — CANCELLED appointments do NOT reduce free slots")
    void getDailySchedule_cancelledAppointment_doesNotReduceSlots() {
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any()))
                .thenReturn(List.of(cancelledAppt));

        ScheduleDayResponse response = scheduleService.getDailySchedule(DOCTOR_ID, DATE);

        assertThat(response.getFreeSlots()).hasSize(16);  // all 16 still free
    }

    @Test
    @DisplayName("getDailySchedule — NO_SHOW appointments do NOT reduce free slots")
    void getDailySchedule_noShowAppointment_doesNotReduceSlots() {
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any()))
                .thenReturn(List.of(noShowAppt));

        ScheduleDayResponse response = scheduleService.getDailySchedule(DOCTOR_ID, DATE);

        assertThat(response.getFreeSlots()).hasSize(16);
    }

    @Test
    @DisplayName("getDailySchedule — free slot start/end times are 30-min intervals within working hours")
    void getDailySchedule_freeSlotBoundsAreCorrect() {
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any())).thenReturn(List.of());

        ScheduleDayResponse response = scheduleService.getDailySchedule(DOCTOR_ID, DATE);

        ScheduleDayResponse.TimeSlot first = response.getFreeSlots().get(0);
        ScheduleDayResponse.TimeSlot last  = response.getFreeSlots().get(15);
        assertThat(first.getStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(first.getEnd()).isEqualTo(LocalTime.of(9, 30));
        assertThat(last.getStart()).isEqualTo(LocalTime.of(16, 30));
        assertThat(last.getEnd()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("getDailySchedule — response includes date, appointments list, and metadata")
    void getDailySchedule_responseContainsAllFields() {
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any()))
                .thenReturn(List.of(pendingAppt));

        ScheduleDayResponse response = scheduleService.getDailySchedule(DOCTOR_ID, DATE);

        assertThat(response.getDate()).isEqualTo(DATE);
        assertThat(response.getAppointments()).hasSize(1);
        assertThat(response.getAppointments().get(0)).isNotNull();
        assertThat(response.getFreeSlots()).hasSize(15);
    }

    // ─── getScheduleSummary ───────────────────────────────────────────────────

    @Test
    @DisplayName("getScheduleSummary — correctly maps COMPLETED/CONFIRMED/NO_SHOW counts")
    void getScheduleSummary_returnsCorrectStatusCounts() {
        when(appointmentRepository.countByDoctorIdAndDateAndStatus(eq(DOCTOR_ID), any(), any(), eq(AppointmentStatus.COMPLETED))).thenReturn(3L);
        when(appointmentRepository.countByDoctorIdAndDateAndStatus(eq(DOCTOR_ID), any(), any(), eq(AppointmentStatus.CONFIRMED))).thenReturn(4L);
        when(appointmentRepository.countByDoctorIdAndDateAndStatus(eq(DOCTOR_ID), any(), any(), eq(AppointmentStatus.NO_SHOW))).thenReturn(1L);
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any())).thenReturn(List.of());

        ScheduleSummaryResponse response = scheduleService.getScheduleSummary(DOCTOR_ID, DATE);

        assertThat(response.getDone()).isEqualTo(3);
        assertThat(response.getUpcoming()).isEqualTo(4);
        assertThat(response.getNoShow()).isEqualTo(1);
    }

    @Test
    @DisplayName("getScheduleSummary — no custom hours → totalSlots=16 (default 8-hour day)")
    void getScheduleSummary_defaultHours_sixteenTotalSlots() {
        when(appointmentRepository.countByDoctorIdAndDateAndStatus(any(), any(), any(), any())).thenReturn(0L);
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any())).thenReturn(List.of());

        ScheduleSummaryResponse response = scheduleService.getScheduleSummary(DOCTOR_ID, DATE);

        assertThat(response.getFreeSlots()).isEqualTo(16);
    }

    @Test
    @DisplayName("getScheduleSummary — custom 4-hour window → totalSlots=8, minus taken appointments")
    void getScheduleSummary_customHours_correctFreeSlotCalculation() {
        // 08:00–12:00 = 4 hours = 8 slots; 2 non-cancelled taken → 6 free
        DoctorWorkingHours hours = buildHours(LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(appointmentRepository.countByDoctorIdAndDateAndStatus(any(), any(), any(), any())).thenReturn(0L);
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of(hours));
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any()))
                .thenReturn(List.of(pendingAppt, confirmedAppt));  // 2 taken at 9:00 and 10:00

        ScheduleSummaryResponse response = scheduleService.getScheduleSummary(DOCTOR_ID, DATE);

        assertThat(response.getFreeSlots()).isEqualTo(6);
    }

    @Test
    @DisplayName("getScheduleSummary — CANCELLED appointments do not count as taken slots")
    void getScheduleSummary_cancelledNotCountedAsTaken() {
        when(appointmentRepository.countByDoctorIdAndDateAndStatus(any(), any(), any(), any())).thenReturn(0L);
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of());
        // Only cancelled appointments → 0 taken → 16 free
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any()))
                .thenReturn(List.of(cancelledAppt, noShowAppt));

        ScheduleSummaryResponse response = scheduleService.getScheduleSummary(DOCTOR_ID, DATE);

        assertThat(response.getFreeSlots()).isEqualTo(16);
    }

    @Test
    @DisplayName("getScheduleSummary — freeSlots is never negative when more taken than slots")
    void getScheduleSummary_freeSlotsNeverNegative() {
        // 1-hour window = 2 total slots; 5 appointments taken → max(0, 2-5) = 0
        DoctorWorkingHours hours = buildHours(LocalTime.of(9, 0), LocalTime.of(10, 0));
        when(appointmentRepository.countByDoctorIdAndDateAndStatus(any(), any(), any(), any())).thenReturn(0L);
        when(workingHoursRepository.findByDoctorIdAndDayOfWeek(DOCTOR_ID, DOW)).thenReturn(List.of(hours));
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(), any()))
                .thenReturn(List.of(pendingAppt, confirmedAppt, completedAppt, cancelledAppt, noShowAppt));
        // Only 3 non-cancelled (pending, confirmed, completed) but 2 > totalSlots=2 → freeSlots=0

        ScheduleSummaryResponse response = scheduleService.getScheduleSummary(DOCTOR_ID, DATE);

        assertThat(response.getFreeSlots()).isGreaterThanOrEqualTo(0);
    }

    // ─── getWeeklySummary ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getWeeklySummary — always returns exactly 7 entries starting from weekOf")
    void getWeeklySummary_alwaysReturnsSeven() {
        lenient().when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                anyLong(), any(), any())).thenReturn(List.of());

        Map<String, Long> result = scheduleService.getWeeklySummary(DOCTOR_ID, DATE);

        assertThat(result).hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(result).containsKey(DATE.plusDays(i).toString());
        }
    }

    @Test
    @DisplayName("getWeeklySummary — days with no appointments return count 0")
    void getWeeklySummary_noDays_allZero() {
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                anyLong(), any(), any())).thenReturn(List.of());

        Map<String, Long> result = scheduleService.getWeeklySummary(DOCTOR_ID, DATE);

        assertThat(result.values()).containsOnly(0L);
    }

    @Test
    @DisplayName("getWeeklySummary — CANCELLED appointments not counted in day totals")
    void getWeeklySummary_cancelledNotCounted() {
        // Return one CANCELLED appointment for every day
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                anyLong(), any(), any())).thenReturn(List.of(cancelledAppt));

        Map<String, Long> result = scheduleService.getWeeklySummary(DOCTOR_ID, DATE);

        assertThat(result.values()).containsOnly(0L);
    }

    @Test
    @DisplayName("getWeeklySummary — non-cancelled appointments are counted per day")
    void getWeeklySummary_activeAppointmentsCounted() {
        // 2 active (PENDING + CONFIRMED) per day
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                anyLong(), any(), any())).thenReturn(List.of(pendingAppt, confirmedAppt));

        Map<String, Long> result = scheduleService.getWeeklySummary(DOCTOR_ID, DATE);

        assertThat(result.values()).containsOnly(2L);
    }

    // ─── getUpNext ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUpNext — no upcoming CONFIRMED appointment returns null")
    void getUpNext_noUpcoming_returnsNull() {
        when(appointmentRepository.findFirstByDoctorIdAndScheduledAtAfterAndStatusOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(LocalDateTime.class), eq(AppointmentStatus.CONFIRMED)))
                .thenReturn(Optional.empty());

        AppointmentResponse result = scheduleService.getUpNext(DOCTOR_ID);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getUpNext — returns the next CONFIRMED appointment mapped to AppointmentResponse")
    void getUpNext_upcomingExists_returnsMappedResponse() {
        when(appointmentRepository.findFirstByDoctorIdAndScheduledAtAfterAndStatusOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(LocalDateTime.class), eq(AppointmentStatus.CONFIRMED)))
                .thenReturn(Optional.of(confirmedAppt));

        AppointmentResponse result = scheduleService.getUpNext(DOCTOR_ID);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("getUpNext — queries only CONFIRMED status (not PENDING or other states)")
    void getUpNext_queriesOnlyConfirmedStatus() {
        when(appointmentRepository.findFirstByDoctorIdAndScheduledAtAfterAndStatusOrderByScheduledAtAsc(
                anyLong(), any(), any())).thenReturn(Optional.empty());

        scheduleService.getUpNext(DOCTOR_ID);

        verify(appointmentRepository).findFirstByDoctorIdAndScheduledAtAfterAndStatusOrderByScheduledAtAsc(
                eq(DOCTOR_ID), any(LocalDateTime.class), eq(AppointmentStatus.CONFIRMED));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Appointment appt(Doctor doctor, User patient, LocalDateTime scheduledAt, AppointmentStatus status) {
        return Appointment.builder()
                .id((long) (Math.random() * 1000 + 1))
                .doctor(doctor).patient(patient)
                .scheduledAt(scheduledAt)
                .endTime(scheduledAt.plusMinutes(30))
                .status(status).build();
    }

    private DoctorWorkingHours buildHours(LocalTime start, LocalTime end) {
        return DoctorWorkingHours.builder()
                .startTime(start).endTime(end).dayOfWeek(DOW).build();
    }
}
