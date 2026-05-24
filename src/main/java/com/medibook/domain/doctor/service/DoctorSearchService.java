package com.medibook.domain.doctor.service;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.common.exception.MediBookException;
import com.medibook.config.HospitalProperties;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.dto.AvailabilityGridResponse;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.domain.schedule.service.DoctorLeaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import jakarta.persistence.criteria.JoinType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DoctorSearchService {

    private final DoctorRepository doctorRepository;
    private final DoctorWorkingHoursRepository workingHoursRepository;
    private final AppointmentRepository appointmentRepository;
    private final com.medibook.domain.appointment.service.AppointmentHoldService holdService;
    private final HospitalProperties hospitalProperties;
    private final DoctorLeaveService doctorLeaveService;
    private final com.medibook.domain.schedule.service.DoctorSlotBlockService doctorSlotBlockService;

    @Transactional(readOnly = true)
    public Page<DoctorResponse> searchDoctors(
            String query,
            List<Long> departmentIds,
            List<String> specialisations,
            String availability,
            String visitType,
            Boolean acceptingNew,
            Pageable pageable) {
        Pageable sanitizedPageable = sanitizePageable(pageable);

        Specification<Doctor> spec = Specification.where(null);

        if (query != null && !query.isBlank()) {
            String booleanQuery = toBooleanModeQuery(query);
            if (booleanQuery == null) {
                return Page.empty(sanitizedPageable);
            }
            List<Long> matchedIds = doctorRepository.findIdsByFullText(booleanQuery);
            if (matchedIds.isEmpty()) {
                return Page.empty(sanitizedPageable);
            }
            spec = spec.and((root, q, cb) -> root.get("id").in(matchedIds));
        }

        if (departmentIds != null && !departmentIds.isEmpty()) {
            final List<Long> deptIds = departmentIds;
            spec = spec.and((root, q, cb) -> {
                if (!q.getResultType().equals(Long.class)) {
                    q.distinct(true);
                }
                return cb.or(
                    root.get("department").get("id").in(deptIds),
                    root.join("additionalDepartments", JoinType.LEFT).get("id").in(deptIds)
                );
            });
        }

        if (specialisations != null && !specialisations.isEmpty()) {
            final List<String> specs = specialisations;
            spec = spec.and((root, q, cb) -> {
                if (!q.getResultType().equals(Long.class)) {
                    q.distinct(true);
                }
                return cb.or(
                    root.get("specialization").in(specs),
                    root.join("specializations", JoinType.LEFT).in(specs)
                );
            });
        }

        if (acceptingNew != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("acceptingNew"), acceptingNew));
        }

        spec = spec.and((root, q, cb) -> cb.isTrue(root.get("isActive")));

        Page<Doctor> doctors = doctorRepository.findAll(spec, sanitizedPageable);
        Map<Long, List<DoctorWorkingHours>> hoursByDoctor = loadWorkingHours(doctors.getContent());
        return doctors.map(d -> DoctorResponse.fromEntity(
                d,
                hospitalProperties,
                hoursByDoctor.getOrDefault(d.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public DoctorResponse getDoctorById(Long id) {
        return doctorRepository.findByIdWithDetails(id)
                .map(d -> DoctorResponse.fromEntity(
                        d,
                        hospitalProperties,
                        workingHoursRepository.findByDoctorId(d.getId())))
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
    }

    @Transactional(readOnly = true)
    public AvailabilityGridResponse getAvailability(Long doctorId, LocalDate from, LocalDate to) {
        validateAvailabilityWindow(from, to);
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));
        // Slot duration: doctor override → department default → 30 (matches DoctorScheduleService).
        // Step: slot duration + department buffer so the grid reflects the realistic cadence
        // (consult time + cleaning/prep), and any configured midday break falls out naturally
        // because each working-hours shift is iterated separately.
        int slotDuration = resolveSlotDurationMins(doctor);
        int bufferMins   = resolveBufferMins(doctor);
        int stepMins     = slotDuration + bufferMins;
        LocalDateTime now = LocalDateTime.now();

        List<DoctorWorkingHours> workingHours = workingHoursRepository.findByDoctorId(doctorId);

        List<Appointment> bookedAppointments = appointmentRepository
                .findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                        doctorId, from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .filter(a -> a.getStatus() != com.medibook.domain.appointment.entity.AppointmentStatus.CANCELLED)
                .filter(a -> a.getStatus() != com.medibook.domain.appointment.entity.AppointmentStatus.NO_SHOW)
                .toList();

        List<LocalDateTime> allSlotStarts = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            for (DoctorWorkingHours hours : workingHours) {
                if (hours.getDayOfWeek() != dayOfWeek) continue;
                LocalTime current = hours.getStartTime();
                // A slot only fits if (current + slotDuration) is still inside the shift —
                // otherwise we'd offer a window that bleeds past closing time.
                while (!current.plusMinutes(slotDuration).isAfter(hours.getEndTime())) {
                    LocalDateTime slotStart = date.atTime(current);
                    if (!slotStart.isBefore(now)) {
                        allSlotStarts.add(slotStart);
                    }
                    current = current.plusMinutes(stepMins);
                }
            }
        }

        Set<LocalDateTime> heldSlots = holdService.getHeldSlots(doctorId, allSlotStarts);

        // Ad-hoc per-day blocks the doctor declared (e.g. "operating on patient X").
        // Pulled once for the whole window — overlap checks per slot are O(blocks)
        // which is tiny in practice.
        var slotBlocks = doctorSlotBlockService.findRawForDoctor(doctorId, from, to);

        List<AvailabilityGridResponse.DaySlots> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            boolean onLeave = doctorLeaveService.isDoctorOnLeave(doctorId, date);
            final LocalDate dateF = date;
            var blocksForDay = slotBlocks.stream()
                    .filter(b -> b.getBlockDate().equals(dateF))
                    .toList();
            List<AvailabilityGridResponse.SlotInfo> slots = new ArrayList<>();

            for (DoctorWorkingHours hours : workingHours) {
                if (hours.getDayOfWeek() != dayOfWeek) continue;
                LocalTime current = hours.getStartTime();
                while (!current.plusMinutes(slotDuration).isAfter(hours.getEndTime())) {
                    LocalDateTime start = date.atTime(current);
                    LocalDateTime end   = start.plusMinutes(slotDuration);
                    final LocalTime curF = current;
                    final LocalTime endF = current.plusMinutes(slotDuration);
                    boolean isBlocked = blocksForDay.stream()
                            .anyMatch(b -> curF.isBefore(b.getEndTime()) && endF.isAfter(b.getStartTime()));

                    // Mark past slots as PAST instead of excluding them entirely
                    // so the frontend can grey them out for visual context.
                    String status;
                    if (start.isBefore(now)) {
                        status = "PAST";
                    } else if (onLeave) {
                        status = "ON_LEAVE";
                    } else if (overlapsAnyAppointment(start, end, bookedAppointments)) {
                        status = "TAKEN";
                    } else if (isBlocked) {
                        status = "BLOCKED";
                    } else if (heldSlots.contains(start)) {
                        status = "HELD";
                    } else {
                        status = "OPEN";
                    }

                    slots.add(AvailabilityGridResponse.SlotInfo.builder()
                            .start(start).end(end).status(status).build());

                    current = current.plusMinutes(stepMins);
                }
            }

            days.add(AvailabilityGridResponse.DaySlots.builder()
                    .date(date).slots(slots).build());
        }

        return AvailabilityGridResponse.builder().days(days).build();
    }

    /** Resolution order: doctor override → department default → global default (30). */
    private int resolveSlotDurationMins(Doctor doctor) {
        if (doctor.getSlotDurationMins() > 0) return doctor.getSlotDurationMins();
        if (doctor.getDepartment() != null && doctor.getDepartment().getSlotDurationMins() > 0)
            return doctor.getDepartment().getSlotDurationMins();
        return 30;
    }

    private int resolveBufferMins(Doctor doctor) {
        if (doctor.getDepartment() != null) {
            return Math.max(0, doctor.getDepartment().getBufferMins());
        }
        return 0;
    }

    private boolean overlapsAnyAppointment(LocalDateTime start, LocalDateTime end, List<Appointment> appointments) {
        return appointments.stream().anyMatch(a -> {
            LocalDateTime appointmentEnd = a.getEndTime() != null
                    ? a.getEndTime()
                    : a.getScheduledAt().plusMinutes(a.getDurationMins());
            return a.getScheduledAt().isBefore(end) && appointmentEnd.isAfter(start);
        });
    }

    private Pageable sanitizePageable(Pageable pageable) {
        int size = Math.min(Math.max(pageable.getPageSize(), 1), 50);
        Sort sort = pageable.getSort().isSorted()
                ? pageable.getSort()
                : Sort.by(
                        Sort.Order.desc("averageRating"),
                        Sort.Order.desc("reviewCount"),
                        Sort.Order.asc("id"));
        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }

    private Map<Long, List<DoctorWorkingHours>> loadWorkingHours(List<Doctor> doctors) {
        if (doctors == null || doctors.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = doctors.stream().map(Doctor::getId).toList();
        List<DoctorWorkingHours> rows = workingHoursRepository.findByDoctorIds(ids);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream()
                .collect(Collectors.groupingBy(h -> h.getDoctor().getId()));
    }

    private void validateAvailabilityWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new MediBookException("Availability date range is required.",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }
        if (to.isBefore(from)) {
            throw new MediBookException("Availability end date must be on or after start date.",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 31) {
            throw new MediBookException("Availability range cannot exceed 31 days.",
                    HttpStatus.BAD_REQUEST, "AVAILABILITY_RANGE_TOO_LARGE");
        }
    }

    private String toBooleanModeQuery(String query) {
        String normalized = query
                .trim()
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s-]", " ")
                .replaceAll("\\s+", " ");
        if (normalized.length() < 2) {
            return null;
        }

        String booleanQuery = Arrays.stream(normalized.split(" "))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .limit(5)
                .map(token -> token + "*")
                .collect(Collectors.joining(" "));

        return booleanQuery.isBlank() ? null : booleanQuery;
    }
}
