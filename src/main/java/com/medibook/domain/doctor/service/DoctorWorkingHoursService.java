package com.medibook.domain.doctor.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.doctor.dto.WorkingHoursRequest;
import com.medibook.domain.doctor.dto.WorkingHoursResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DoctorWorkingHoursService {

    private final DoctorRepository            doctorRepository;
    private final DoctorWorkingHoursRepository workingHoursRepository;

    @Transactional(readOnly = true)
    public List<WorkingHoursResponse> getByDoctorId(Long doctorId) {
        if (!doctorRepository.existsById(doctorId)) {
            throw new ResourceNotFoundException("Doctor", "id", doctorId);
        }
        return workingHoursRepository.findByDoctorId(doctorId).stream()
                .map(WorkingHoursResponse::fromEntity)
                .sorted((a, b) -> Integer.compare(a.getDayOfWeek(), b.getDayOfWeek()))
                .collect(Collectors.toList());
    }

    /**
     * Replaces all working hours for a doctor atomically.
     * Validates: startTime < endTime, no duplicate days.
     */
    @CacheEvict(value = "doctors", key = "#doctorId")
    @Transactional
    public List<WorkingHoursResponse> replaceAll(Long doctorId, WorkingHoursRequest request) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));

        validate(request);

        List<DoctorWorkingHours> existing = workingHoursRepository.findByDoctorId(doctorId);
        workingHoursRepository.deleteAll(existing);

        List<DoctorWorkingHours> newHours = request.getSchedule().stream()
                .map(d -> DoctorWorkingHours.builder()
                        .doctor(doctor)
                        .dayOfWeek(d.getDayOfWeek())
                        .startTime(d.getStartTime())
                        .endTime(d.getEndTime())
                        .build())
                .collect(Collectors.toList());

        return workingHoursRepository.saveAll(newHours).stream()
                .map(WorkingHoursResponse::fromEntity)
                .sorted((a, b) -> Integer.compare(a.getDayOfWeek(), b.getDayOfWeek()))
                .collect(Collectors.toList());
    }

    @CacheEvict(value = "doctors", key = "#doctorId")
    @Transactional
    public List<WorkingHoursResponse> replaceAll(Long doctorId, WorkingHoursRequest request, UserPrincipal principal) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));
        ensureCanManageDoctor(doctor, principal);
        validate(request);

        List<DoctorWorkingHours> existing = workingHoursRepository.findByDoctorId(doctorId);
        workingHoursRepository.deleteAll(existing);

        List<DoctorWorkingHours> newHours = request.getSchedule().stream()
                .map(d -> DoctorWorkingHours.builder()
                        .doctor(doctor)
                        .dayOfWeek(d.getDayOfWeek())
                        .startTime(d.getStartTime())
                        .endTime(d.getEndTime())
                        .build())
                .collect(Collectors.toList());

        return workingHoursRepository.saveAll(newHours).stream()
                .map(WorkingHoursResponse::fromEntity)
                .sorted((a, b) -> Integer.compare(a.getDayOfWeek(), b.getDayOfWeek()))
                .collect(Collectors.toList());
    }

    private void validate(WorkingHoursRequest request) {
        Set<Integer> seen = new java.util.HashSet<>();
        for (WorkingHoursRequest.DaySchedule day : request.getSchedule()) {
            if (!seen.add(day.getDayOfWeek())) {
                throw new MediBookException(
                        "Duplicate day of week: " + day.getDayOfWeek(),
                        HttpStatus.BAD_REQUEST, "DUPLICATE_DAY");
            }
            if (!day.getStartTime().isBefore(day.getEndTime())) {
                throw new MediBookException(
                        "Start time must be before end time for day " + day.getDayOfWeek(),
                        HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE");
            }
        }
    }

    private void ensureCanManageDoctor(Doctor doctor, UserPrincipal principal) {
        if (principal.hasRole("ROLE_ADMIN")) {
            return;
        }
        if (!doctor.getUser().getId().equals(principal.getId())) {
            throw new MediBookException("Not authorized to manage this doctor schedule",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }
}
