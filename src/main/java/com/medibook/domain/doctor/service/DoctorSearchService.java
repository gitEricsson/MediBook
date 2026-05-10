package com.medibook.domain.doctor.service;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.dto.AvailabilityGridResponse;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DoctorSearchService {

    private final DoctorRepository doctorRepository;
    private final DoctorWorkingHoursRepository workingHoursRepository;
    private final AppointmentRepository appointmentRepository;
    private final com.medibook.domain.appointment.service.AppointmentHoldService holdService;

    @Transactional(readOnly = true)
    public Page<DoctorResponse> searchDoctors(
            String query,
            List<Long> departmentIds,
            List<String> specialisations,
            String availability,
            String visitType,
            Boolean acceptingNew,
            Pageable pageable) {

        Specification<Doctor> spec = Specification.where(null);

        if (query != null && !query.isBlank()) {
            String booleanQuery = query.trim() + "*";
            List<Long> matchedIds = doctorRepository.findIdsByFullText(booleanQuery);
            if (matchedIds.isEmpty()) {
                return Page.empty(pageable);
            }
            spec = spec.and((root, q, cb) -> root.get("id").in(matchedIds));
        }

        if (departmentIds != null && !departmentIds.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("department").get("id").in(departmentIds));
        }

        if (specialisations != null && !specialisations.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("specialization").in(specialisations));
        }

        if (acceptingNew != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("acceptingNew"), acceptingNew));
        }

        spec = spec.and((root, q, cb) -> cb.isTrue(root.get("isActive")));

        return doctorRepository.findAll(spec, pageable).map(DoctorResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public DoctorResponse getDoctorById(Long id) {
        return doctorRepository.findByIdWithDetails(id)
                .map(DoctorResponse::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
    }

    @Transactional(readOnly = true)
    public AvailabilityGridResponse getAvailability(Long doctorId, LocalDate from, LocalDate to) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));
        int slotDuration = doctor.getSlotDurationMins();

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
                while (current.isBefore(hours.getEndTime())) {
                    allSlotStarts.add(date.atTime(current));
                    current = current.plusMinutes(slotDuration);
                }
            }
        }

        Set<LocalDateTime> heldSlots = holdService.getHeldSlots(doctorId, allSlotStarts);

        List<AvailabilityGridResponse.DaySlots> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            List<AvailabilityGridResponse.SlotInfo> slots = new ArrayList<>();

            for (DoctorWorkingHours hours : workingHours) {
                if (hours.getDayOfWeek() != dayOfWeek) continue;
                LocalTime current = hours.getStartTime();
                while (current.isBefore(hours.getEndTime())) {
                    LocalDateTime start = date.atTime(current);
                    LocalDateTime end   = start.plusMinutes(slotDuration); // fixed: was hardcoded 30

                    String status = "OPEN";
                    if (overlapsAnyAppointment(start, end, bookedAppointments)) {
                        status = "TAKEN";
                    } else if (heldSlots.contains(start)) {
                        status = "HELD";
                    }

                    slots.add(AvailabilityGridResponse.SlotInfo.builder()
                            .start(start).end(end).status(status).build());

                    current = current.plusMinutes(slotDuration);
                }
            }

            days.add(AvailabilityGridResponse.DaySlots.builder()
                    .date(date).slots(slots).build());
        }

        return AvailabilityGridResponse.builder().days(days).build();
    }

    private boolean overlapsAnyAppointment(LocalDateTime start, LocalDateTime end, List<Appointment> appointments) {
        return appointments.stream().anyMatch(a -> {
            LocalDateTime appointmentEnd = a.getEndTime() != null
                    ? a.getEndTime()
                    : a.getScheduledAt().plusMinutes(a.getDurationMins());
            return a.getScheduledAt().isBefore(end) && appointmentEnd.isAfter(start);
        });
    }
}
