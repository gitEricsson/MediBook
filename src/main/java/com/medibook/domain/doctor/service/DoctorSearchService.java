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
import java.util.stream.Collectors;

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
            spec = spec.and((root, q, cb) -> 
                cb.or(
                    cb.like(cb.lower(root.get("user").get("firstName")), "%" + query.toLowerCase() + "%"),
                    cb.like(cb.lower(root.get("user").get("lastName")), "%" + query.toLowerCase() + "%"),
                    cb.like(cb.lower(root.get("specialization")), "%" + query.toLowerCase() + "%")
                )
            );
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

        // Always only show active doctors
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

        List<AvailabilityGridResponse.DaySlots> days = new ArrayList<>();
        
        List<DoctorWorkingHours> workingHours = workingHoursRepository.findByDoctorId(doctorId);
        List<Appointment> existingAppointments = appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                doctorId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            final LocalDate currentDate = date;
            int dayOfWeek = date.getDayOfWeek().getValue();
            
            List<DoctorWorkingHours> dailyHours = workingHours.stream()
                    .filter(h -> h.getDayOfWeek() == dayOfWeek)
                    .collect(Collectors.toList());

            List<AvailabilityGridResponse.SlotInfo> slots = new ArrayList<>();

            for (DoctorWorkingHours hours : dailyHours) {
                LocalTime current = hours.getStartTime();
                while (current.isBefore(hours.getEndTime())) {
                    LocalDateTime start = currentDate.atTime(current);
                    LocalDateTime end = start.plusMinutes(30);

                    String status = "OPEN";
                    final LocalDateTime finalStart = start;
                    if (existingAppointments.stream().anyMatch(a -> a.getScheduledAt().equals(finalStart))) {
                        status = "TAKEN";
                    } else if (holdService.isSlotHeld(doctorId, finalStart)) {
                        status = "HELD";
                    }

                    slots.add(AvailabilityGridResponse.SlotInfo.builder()
                            .start(start)
                            .end(end)
                            .status(status)
                            .build());
                    
                    current = current.plusMinutes(slotDuration);
                }
            }

            days.add(AvailabilityGridResponse.DaySlots.builder()
                    .date(date)
                    .slots(slots)
                    .build());
        }

        return AvailabilityGridResponse.builder().days(days).build();
    }
}
