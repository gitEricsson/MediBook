package com.medibook.domain.doctor.service;

import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.dto.ScheduleDayResponse;
import com.medibook.domain.doctor.dto.ScheduleSummaryResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.common.exception.ResourceNotFoundException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorScheduleService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorWorkingHoursRepository workingHoursRepository;
    private final DoctorRepository doctorRepository;
    private static final int DEFAULT_SLOT_DURATION_MINS = 30;

    @Bulkhead(name = "doctorSchedule")
    @CircuitBreaker(name = "doctorSchedule")
    @Cacheable(value = "doctorSlots", key = "#doctorId + ':' + #date", unless = "#result == null")
    @Transactional(readOnly = true)
    public ScheduleDayResponse getDailySchedule(Long doctorId, LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(23, 59, 59);
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));
        int slotDuration = resolveSlotDurationMins(doctor);
        int bufferMins   = resolveBufferMins(doctor);
        int stepMins     = slotDuration + bufferMins;

        List<Appointment> appointments = appointmentRepository
                .findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(doctorId, startOfDay, endOfDay);

        int dayOfWeek = date.getDayOfWeek().getValue();
        List<DoctorWorkingHours> hoursList = workingHoursRepository.findByDoctorIdAndDayOfWeek(doctorId, dayOfWeek);

        LocalTime workStart = LocalTime.of(9, 0);
        LocalTime workEnd   = LocalTime.of(17, 0);

        if (!hoursList.isEmpty()) {
            workStart = hoursList.get(0).getStartTime();
            workEnd   = hoursList.get(0).getEndTime();
        }

        List<ScheduleDayResponse.TimeSlot> freeSlots = new ArrayList<>();
        LocalDateTime workStartAt = date.atTime(workStart);
        LocalDateTime workEndAt   = date.atTime(workEnd);
        LocalDateTime current = workStartAt;
        while (!current.plusMinutes(slotDuration).isAfter(workEndAt)) {
            final LocalDateTime slotStart = current;
            LocalDateTime slotEnd = slotStart.plusMinutes(slotDuration);
            boolean isTaken = appointments.stream().anyMatch(a -> {
                LocalDateTime appointmentEnd = a.getEndTime() != null
                        ? a.getEndTime()
                        : a.getScheduledAt().plusMinutes(a.getDurationMins());
                return a.getStatus() != AppointmentStatus.CANCELLED &&
                        a.getStatus() != AppointmentStatus.NO_SHOW &&
                        a.getScheduledAt().isBefore(slotEnd) &&
                        appointmentEnd.isAfter(slotStart);
            });
            if (!isTaken) {
                freeSlots.add(ScheduleDayResponse.TimeSlot.builder()
                        .start(slotStart.toLocalTime())
                        .end(slotEnd.toLocalTime())
                        .build());
            }
            current = current.plusMinutes(stepMins);
        }

        return ScheduleDayResponse.builder()
                .date(date)
                .workStart(workStart)
                .workEnd(workEnd)
                .freeSlots(freeSlots)
                .appointments(appointments.stream().map(AppointmentResponse::fromEntity).toList())
                .build();
    }

    /** Evict cached slots when an appointment is booked or cancelled for this doctor/date. */
    @CacheEvict(value = "doctorSlots", key = "#doctorId + ':' + #date")
    public void evictSlotCache(Long doctorId, LocalDate date) {
        log.debug("Evicted slot cache for doctor [{}] on [{}]", doctorId, date);
    }

    @Transactional(readOnly = true)
    public ScheduleSummaryResponse getScheduleSummary(Long doctorId, LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(23, 59, 59);
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));
        int slotDuration = resolveSlotDurationMins(doctor);

        long done = appointmentRepository.countByDoctorIdAndDateAndStatus(doctorId, startOfDay, endOfDay, AppointmentStatus.COMPLETED);
        long upcoming = appointmentRepository.countByDoctorIdAndDateAndStatus(doctorId, startOfDay, endOfDay, AppointmentStatus.CONFIRMED);
        long noShow = appointmentRepository.countByDoctorIdAndDateAndStatus(doctorId, startOfDay, endOfDay, AppointmentStatus.NO_SHOW);
        
        int dayOfWeek = date.getDayOfWeek().getValue();
        List<DoctorWorkingHours> hoursList = workingHoursRepository.findByDoctorIdAndDayOfWeek(doctorId, dayOfWeek);
        long totalPossibleSlots = 480 / slotDuration; // Default 8-hour day
        if (!hoursList.isEmpty()) {
            totalPossibleSlots = Duration.between(hoursList.get(0).getStartTime(), hoursList.get(0).getEndTime()).toMinutes() / slotDuration;
        }
        
        long taken = appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(doctorId, startOfDay, endOfDay)
                .stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED && a.getStatus() != AppointmentStatus.NO_SHOW).count();

        long freeSlots = Math.max(0, totalPossibleSlots - taken);

        return ScheduleSummaryResponse.builder()
                .done(done)
                .upcoming(upcoming)
                .noShow(noShow)
                .freeSlots(freeSlots)
                .build();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getWeeklySummary(Long doctorId, LocalDate weekOf) {
        Map<String, Long> weeklyCounts = new HashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekOf.plusDays(i);
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.atTime(23, 59, 59);
            long count = appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(doctorId, start, end)
                    .stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).count();
            weeklyCounts.put(date.toString(), count);
        }
        return weeklyCounts;
    }

    @Transactional(readOnly = true)
    public AppointmentResponse getUpNext(Long doctorId) {
        Optional<Appointment> next = appointmentRepository.findFirstByDoctorIdAndScheduledAtAfterAndStatusOrderByScheduledAtAsc(
                doctorId, LocalDateTime.now(), AppointmentStatus.CONFIRMED);
        return next.map(AppointmentResponse::fromEntity).orElse(null);
    }

    /**
     * Resolution order: doctor override → department default → global default.
     */
    private int resolveSlotDurationMins(Doctor doctor) {
        if (doctor.getSlotDurationMins() > 0) {
            return doctor.getSlotDurationMins();
        }
        if (doctor.getDepartment() != null && doctor.getDepartment().getSlotDurationMins() > 0) {
            return doctor.getDepartment().getSlotDurationMins();
        }
        log.warn("Doctor [{}] and department have no valid slotDurationMins; using default {} min",
                doctor.getId(), DEFAULT_SLOT_DURATION_MINS);
        return DEFAULT_SLOT_DURATION_MINS;
    }

    private int resolveBufferMins(Doctor doctor) {
        if (doctor.getDepartment() != null) {
            return Math.max(0, doctor.getDepartment().getBufferMins());
        }
        return 0;
    }
}
