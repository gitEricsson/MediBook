package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.domain.schedule.repository.DoctorSlotBlockRepository;
import com.medibook.domain.schedule.service.DoctorLeaveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Booking-gate edge cases. Every "patient at 3 AM / doctor double-booked" lawsuit
 * lives in this policy, so the test surface is intentionally exhaustive.
 *
 * Fixture: doctor with two shifts on Monday (09:00–13:00, 14:00–18:00), 30-min slots,
 * shop closed on Sunday.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentSchedulingPolicy — Unit Tests")
class AppointmentSchedulingPolicyTest {

    @Mock DoctorRepository              doctorRepository;
    @Mock DoctorWorkingHoursRepository  workingHoursRepo;
    @Mock DoctorLeaveService            doctorLeaveService;
    @Mock AppointmentRepository         appointmentRepository;
    @Mock DoctorSlotBlockRepository     slotBlockRepository;
    @InjectMocks AppointmentSchedulingPolicy policy;

    private static final long DOCTOR_ID = 1L;

    // Always pick a Monday at least 14 days out so SLOT_IN_PAST never trips.
    private static final LocalDate MONDAY = LocalDate.now()
            .plusDays(14)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    private Doctor activeDoctor;

    @BeforeEach
    void setUp() {
        activeDoctor = Doctor.builder().id(DOCTOR_ID).slotDurationMins(30).isActive(true).build();
        lenient().when(doctorRepository.findById(DOCTOR_ID)).thenReturn(Optional.of(activeDoctor));
        lenient().when(doctorLeaveService.isDoctorOnLeave(anyLong(), any(LocalDate.class))).thenReturn(false);
        lenient().when(slotBlockRepository.findByDoctorIdAndBlockDateBetweenOrderByBlockDateAscStartTimeAsc(
                anyLong(), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        lenient().when(workingHoursRepo.findByDoctorIdAndDayOfWeek(eq(DOCTOR_ID), eq(1)))
                .thenReturn(List.of(
                        shift(LocalTime.of(9, 0),  LocalTime.of(13, 0)),
                        shift(LocalTime.of(14, 0), LocalTime.of(18, 0))));
    }

    @Nested @DisplayName("Past/null/range validation")
    class PastAndRange {
        @Test void rejectsNullScheduledAt() {
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, null))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("scheduledAt is required");
        }
        @Test void rejectsTimeInPast() {
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, LocalDateTime.now().minusHours(1)))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("past");
        }
        @Test void rejectsEndBeforeStart() {
            LocalDateTime start = MONDAY.atTime(10, 0);
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, start, start.minusMinutes(30)))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("End time must be after");
        }
    }

    @Nested @DisplayName("Doctor state")
    class DoctorState {
        @Test void rejectsUnknownDoctor() {
            when(doctorRepository.findById(999L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> policy.checkBookable(999L, MONDAY.atTime(10, 0)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
        @Test void rejectsInactiveDoctor() {
            activeDoctor.setActive(false);
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, MONDAY.atTime(10, 0)))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("not currently accepting");
        }
        @Test void rejectsDoctorOnLeave() {
            when(doctorLeaveService.isDoctorOnLeave(DOCTOR_ID, MONDAY)).thenReturn(true);
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, MONDAY.atTime(10, 0)))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("on leave");
        }
    }

    @Nested @DisplayName("Working-hours containment")
    class WorkingHours {
        @Test void acceptsTimeInsideMorningShift() {
            assertThatCode(() -> policy.checkBookable(DOCTOR_ID, MONDAY.atTime(10, 0)))
                    .doesNotThrowAnyException();
        }
        @Test void acceptsExactShiftStart() {
            assertThatCode(() -> policy.checkBookable(DOCTOR_ID, MONDAY.atTime(9, 0)))
                    .doesNotThrowAnyException();
        }
        @Test void rejectsBeforeShiftStart() {
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, MONDAY.atTime(8, 45)))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("working hours");
        }
        @Test void rejectsLunchBreakStraddle_13_30start() {
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, MONDAY.atTime(13, 30)))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("working hours");
        }
        @Test void rejectsStartInsideButEndAfterShift() {
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, MONDAY.atTime(12, 50)))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("working hours");
        }
        @Test void rejectsClosedDay_Sunday() {
            when(workingHoursRepo.findByDoctorIdAndDayOfWeek(eq(DOCTOR_ID), eq(7))).thenReturn(List.of());
            LocalDate sunday = MONDAY.plusDays(6);   // Monday + 6 = Sunday
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, sunday.atTime(10, 0)))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("does not work");
        }
        @Test void rejectsCrossMidnightWindow() {
            // Without the LocalDateTime-anchored fix this would pass (00:30 < 18:00).
            LocalDateTime start = MONDAY.atTime(23, 30);
            LocalDateTime end   = start.plusMinutes(60);
            assertThatThrownBy(() -> policy.checkBookable(DOCTOR_ID, start, end))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("working hours");
        }
        @Test void acceptsCustomShortWindowInsideShift() {
            LocalDateTime start = MONDAY.atTime(10, 0);
            LocalDateTime end   = start.plusMinutes(15);   // 15-min consult inside shift
            assertThatCode(() -> policy.checkBookable(DOCTOR_ID, start, end))
                    .doesNotThrowAnyException();
        }
    }

    @Nested @DisplayName("Overlap with existing appointments")
    class Overlap {
        @Test void rejectsOverlappingConfirmedAppointment() {
            LocalDateTime start = MONDAY.atTime(10, 0);
            LocalDateTime end   = start.plusMinutes(30);
            when(appointmentRepository.existsConflict(DOCTOR_ID, start, end)).thenReturn(true);
            assertThatThrownBy(() -> policy.checkBookableWithOverlap(DOCTOR_ID, start, end))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("not available");
        }
        @Test void passesWhenNoConflict() {
            LocalDateTime start = MONDAY.atTime(10, 0);
            LocalDateTime end   = start.plusMinutes(30);
            when(appointmentRepository.existsConflict(DOCTOR_ID, start, end)).thenReturn(false);
            assertThatCode(() -> policy.checkBookableWithOverlap(DOCTOR_ID, start, end))
                    .doesNotThrowAnyException();
        }
        @Test void defaultSlotOverlapUsesDoctorSlotDuration() {
            LocalDateTime start = MONDAY.atTime(10, 0);
            when(appointmentRepository.existsConflict(DOCTOR_ID, start, start.plusMinutes(30)))
                    .thenReturn(true);
            assertThatThrownBy(() -> policy.checkOverlapForDefaultSlot(DOCTOR_ID, start))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("not available");
        }
    }
    private DoctorWorkingHours shift(LocalTime start, LocalTime end) {
        return DoctorWorkingHours.builder()
                .doctor(activeDoctor)
                .dayOfWeek(1)
                .startTime(start)
                .endTime(end)
                .build();
    }
    // stays minimal and Checkstyle doesn't complain about a wildcard.
    private static <T> T eq(T value) { return org.mockito.ArgumentMatchers.eq(value); }
}
