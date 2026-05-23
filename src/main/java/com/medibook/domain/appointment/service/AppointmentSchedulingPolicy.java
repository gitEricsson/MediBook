package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.domain.schedule.service.DoctorLeaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Single source of truth for "is the patient allowed to book this doctor at this time?"
 *
 * Used at both gates:
 *   1. AppointmentHoldService.holdSlot — fail fast so the patient sees the error before
 *      seeing the "slot reserved" UI.
 *   2. AppointmentService.book — final check just before the row is written, in case
 *      something changed between hold and confirm (doctor went on leave, etc.).
 *
 * Validates, in order:
 *   - time is not in the past
 *   - doctor exists + is active
 *   - doctor is not on leave that day
 *   - start + (start + slotDurationMins) fall inside one configured working-hours shift
 *   - no overlapping appointment already exists (only checked at book time via existsConflict)
 *
 * Throws a {@link MediBookException} with a descriptive error code so the FE can map to
 * a friendly toast.
 */
@Service
@RequiredArgsConstructor
public class AppointmentSchedulingPolicy {

    private final DoctorRepository doctorRepository;
    private final DoctorWorkingHoursRepository workingHoursRepo;
    private final DoctorLeaveService doctorLeaveService;
    private final AppointmentRepository appointmentRepository;
    private final com.medibook.domain.schedule.repository.DoctorSlotBlockRepository slotBlockRepository;

    /** Pre-hold gate using the doctor's default slot duration. */
    @Transactional(readOnly = true)
    public void checkBookable(Long doctorId, LocalDateTime scheduledAt) {
        checkBookable(doctorId, scheduledAt, /*end*/ null);
    }

    /**
     * Pre-hold gate with an explicit end time. Used by the manual start/end picker so
     * the patient's chosen window — not the doctor's default slot length — is what we
     * validate against working hours.
     */
    @Transactional(readOnly = true)
    public void checkBookable(Long doctorId, LocalDateTime scheduledAt, LocalDateTime end) {
        if (scheduledAt == null) {
            throw new MediBookException("scheduledAt is required",
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        if (scheduledAt.isBefore(LocalDateTime.now())) {
            throw new MediBookException("Cannot book a time slot in the past.",
                    HttpStatus.BAD_REQUEST, "SLOT_IN_PAST");
        }
        if (end != null && !scheduledAt.isBefore(end)) {
            throw new MediBookException("End time must be after start time",
                    HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE");
        }

        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));

        if (!doctor.isActive()) {
            throw new MediBookException("Doctor is not currently accepting appointments",
                    HttpStatus.CONFLICT, "DOCTOR_INACTIVE");
        }
        if (doctorLeaveService.isDoctorOnLeave(doctorId, scheduledAt.toLocalDate())) {
            throw new MediBookException("Doctor is on leave on the requested date",
                    HttpStatus.CONFLICT, "DOCTOR_ON_LEAVE");
        }

        LocalDateTime windowEnd = end != null
                ? end
                : scheduledAt.plusMinutes(resolveSlotDurationMins(doctor));
        ensureWithinWorkingHours(doctorId, scheduledAt, windowEnd);
        ensureNotBlocked(doctorId, scheduledAt, windowEnd);
    }

    /**
     * Reject a booking whose window overlaps an ad-hoc slot block declared by
     * the doctor (e.g. "operating on patient X"). Distinct from a multi-day
     * leave — those are caught by {@code doctorLeaveService.isDoctorOnLeave}.
     */
    private void ensureNotBlocked(Long doctorId, LocalDateTime start, LocalDateTime end) {
        java.time.LocalDate day = start.toLocalDate();
        var blocks = slotBlockRepository.findByDoctorIdAndBlockDateBetweenOrderByBlockDateAscStartTimeAsc(
                doctorId, day, day);
        if (blocks.isEmpty()) return;
        java.time.LocalTime s = start.toLocalTime();
        java.time.LocalTime e = end.toLocalTime();
        boolean overlaps = blocks.stream().anyMatch(b ->
                s.isBefore(b.getEndTime()) && e.isAfter(b.getStartTime()));
        if (overlaps) {
            throw new MediBookException(
                    "That time conflicts with an unavailability the doctor has set for the day.",
                    HttpStatus.CONFLICT, "SLOT_BLOCKED");
        }
    }

    /**
     * Overlap check for the grid path where the patient hasn't supplied an end time —
     * we compute it from the doctor's configured {@code slotDurationMins}.
     */
    @Transactional(readOnly = true)
    public void checkOverlapForDefaultSlot(Long doctorId, LocalDateTime start) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));
        int duration = resolveSlotDurationMins(doctor);
        if (appointmentRepository.existsConflict(doctorId, start, start.plusMinutes(duration))) {
            throw new MediBookException("Doctor is not available for booking at this time",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }
    }

    /** End-to-end gate including overlap check. Used at hold/book/reschedule time. */
    @Transactional(readOnly = true)
    public void checkBookableWithOverlap(Long doctorId, LocalDateTime start, LocalDateTime end) {
        // Pass the explicit window to checkBookable so working-hours validation uses
        // the *actual* end (manual entry), not the doctor's default slot duration.
        checkBookable(doctorId, start, end);
        if (appointmentRepository.existsConflict(doctorId, start, end)) {
            throw new MediBookException("Doctor is not available for booking at this time",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }
    }

    /** Resolution order: doctor override → department default → global default (30). */
    private int resolveSlotDurationMins(Doctor doctor) {
        if (doctor.getSlotDurationMins() > 0) return doctor.getSlotDurationMins();
        if (doctor.getDepartment() != null && doctor.getDepartment().getSlotDurationMins() > 0)
            return doctor.getDepartment().getSlotDurationMins();
        return 30;
    }

    /**
     * Working-hours containment. The window must fit fully inside one configured shift
     * for the weekday of the start date.
     *
     * Compares on {@link LocalDateTime}, not {@link LocalTime}, so a window that crosses
     * midnight (e.g. start 23:30 + 60 min ⇒ end 00:30) is correctly rejected: 00:30 as a
     * LocalTime wraps and would incorrectly pass a "not after 22:00" check, but the
     * LocalDateTime form anchors both ends to a real calendar date.
     */
    private void ensureWithinWorkingHours(Long doctorId, LocalDateTime start, LocalDateTime end) {
        int dow = start.getDayOfWeek().getValue();   // 1=Mon … 7=Sun, matches DB
        List<DoctorWorkingHours> shifts = workingHoursRepo.findByDoctorIdAndDayOfWeek(doctorId, dow);
        if (shifts == null || shifts.isEmpty()) {
            throw new MediBookException(
                    "Doctor does not work on the selected day.",
                    HttpStatus.CONFLICT, "OUTSIDE_WORKING_HOURS");
        }
        java.time.LocalDate day = start.toLocalDate();
        boolean fits = shifts.stream().anyMatch(s -> {
            LocalDateTime shiftStart = day.atTime(s.getStartTime());
            LocalDateTime shiftEnd   = day.atTime(s.getEndTime());
            return !start.isBefore(shiftStart) && !end.isAfter(shiftEnd);
        });
        if (!fits) {
            throw new MediBookException(
                    "Requested time is outside the doctor's working hours for that day.",
                    HttpStatus.CONFLICT, "OUTSIDE_WORKING_HOURS");
        }
    }
}
