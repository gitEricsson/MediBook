package com.medibook.domain.intelligence.service;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Rules-based no-show risk prediction.
 * ML-ready interface: replace score() with a model call without changing callers.
 *
 * IMPORTANT: This is a risk indicator, NOT a clinical decision tool.
 * Scores are for scheduling optimization only. No medical inference is made.
 */
@Service
@RequiredArgsConstructor
public class NoShowPredictionService {

    private final AppointmentRepository appointmentRepository;

    @Transactional(readOnly = true)
    public NoShowRisk predict(Long patientId, Long doctorId, LocalDateTime scheduledAt) {
        List<Appointment> history = appointmentRepository.findByPatientId(patientId, Pageable.ofSize(50))
                .getContent();

        int totalPast = 0, noShows = 0;
        for (Appointment a : history) {
            if (a.getScheduledAt().isBefore(LocalDateTime.now())) {
                totalPast++;
                if (a.getStatus() == AppointmentStatus.NO_SHOW) noShows++;
            }
        }

        double noShowRate = totalPast > 0 ? (double) noShows / totalPast : 0.0;

        double score = 0.0;

        // Factor 1: past no-show rate (weight 0.4)
        score += noShowRate * 0.4;

        // Factor 2: early morning slots have higher no-show risk (weight 0.2)
        int hour = scheduledAt.getHour();
        if (hour < 9) score += 0.2;

        // Factor 3: Monday has higher risk (weight 0.1)
        if (scheduledAt.getDayOfWeek() == DayOfWeek.MONDAY) score += 0.1;

        // Factor 4: first-time patient with no history (weight 0.15)
        if (totalPast == 0) score += 0.15;

        // Factor 5: appointment more than 14 days out (weight 0.15)
        if (scheduledAt.isAfter(LocalDateTime.now().plusDays(14))) score += 0.15;

        score = Math.min(score, 1.0);

        String level = score > 0.6 ? "HIGH" : score > 0.3 ? "MEDIUM" : "LOW";

        return new NoShowRisk(score, level,
                "Rules-based prediction (AI-NOT-CLINICAL). Score based on: prior no-show rate=" +
                        String.format("%.0f%%", noShowRate * 100) + ", scheduling factors.");
    }

    public record NoShowRisk(double score, String level, String explanation) {}
}
