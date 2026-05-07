package com.medibook.domain.doctor.service;

import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.dto.ScheduleDayResponse;
import com.medibook.domain.doctor.dto.ScheduleSummaryResponse;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Bulkhead(name = "doctorSchedule")
    @CircuitBreaker(name = "doctorSchedule")
    @Transactional(readOnly = true)
    public ScheduleDayResponse getDailySchedule(Long doctorId, LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(23, 59, 59);

        List<Appointment> appointments = appointmentRepository
                .findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(doctorId, startOfDay, endOfDay);

        int dayOfWeek = date.getDayOfWeek().getValue();
        List<DoctorWorkingHours> hoursList = workingHoursRepository.findByDoctorIdAndDayOfWeek(doctorId, dayOfWeek);
        
        LocalTime workStart = LocalTime.of(9, 0); // Default fallback
        LocalTime workEnd = LocalTime.of(17, 0);
        
        if (!hoursList.isEmpty()) {
            workStart = hoursList.get(0).getStartTime();
            workEnd = hoursList.get(0).getEndTime();
        }

        List<ScheduleDayResponse.TimeSlot> freeSlots = new ArrayList<>();
        LocalTime current = workStart;
        while (current.isBefore(workEnd)) {
            final LocalTime slotTime = current;
            boolean isTaken = appointments.stream().anyMatch(a -> 
                a.getScheduledAt().toLocalTime().equals(slotTime) && 
                a.getStatus() != AppointmentStatus.CANCELLED && 
                a.getStatus() != AppointmentStatus.NO_SHOW
            );
            if (!isTaken) {
                freeSlots.add(ScheduleDayResponse.TimeSlot.builder()
                        .start(slotTime)
                        .end(slotTime.plusMinutes(30))
                        .build());
            }
            current = current.plusMinutes(30);
        }

        return ScheduleDayResponse.builder()
                .date(date)
                .workStart(workStart)
                .workEnd(workEnd)
                .freeSlots(freeSlots)
                .appointments(appointments.stream().map(AppointmentResponse::fromEntity).toList())
                .build();
    }

    @Transactional(readOnly = true)
    public ScheduleSummaryResponse getScheduleSummary(Long doctorId, LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(23, 59, 59);

        long done = appointmentRepository.countByDoctorIdAndDateAndStatus(doctorId, startOfDay, endOfDay, AppointmentStatus.COMPLETED);
        long upcoming = appointmentRepository.countByDoctorIdAndDateAndStatus(doctorId, startOfDay, endOfDay, AppointmentStatus.CONFIRMED);
        long noShow = appointmentRepository.countByDoctorIdAndDateAndStatus(doctorId, startOfDay, endOfDay, AppointmentStatus.NO_SHOW);
        
        int dayOfWeek = date.getDayOfWeek().getValue();
        List<DoctorWorkingHours> hoursList = workingHoursRepository.findByDoctorIdAndDayOfWeek(doctorId, dayOfWeek);
        long totalPossibleSlots = 16; // Default 8 hours * 2 slots
        if (!hoursList.isEmpty()) {
            totalPossibleSlots = java.time.Duration.between(hoursList.get(0).getStartTime(), hoursList.get(0).getEndTime()).toMinutes() / 30;
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
}
