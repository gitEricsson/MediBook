package com.medibook.jobs;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.waitlist.entity.WaitlistEntry;
import com.medibook.domain.waitlist.repository.WaitlistRepository;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.WaitlistEvent;
import com.medibook.messaging.producer.OutboxEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Promotes waitlist entries when a cancellation opens a slot.
 * Runs every 5 minutes. For each cancelled appointment, checks if any waitlist
 * entry is eligible for the freed slot and creates a new appointment for them.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class WaitlistPromotionJob {

    private final WaitlistRepository    waitlistRepository;
    private final AppointmentRepository appointmentRepository;
    private final OutboxEventProducer   eventProducer;

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @Transactional
    public void promoteWaitlistEntries() {
        // Find recently cancelled appointments that were in the future
        List<Appointment> recentCancellations = appointmentRepository.findByStatus(
                AppointmentStatus.CANCELLED, org.springframework.data.domain.Pageable.ofSize(50))
                .filter(a -> a.getScheduledAt().isAfter(LocalDateTime.now()))
                .toList();

        int promoted = 0;
        for (Appointment cancelled : recentCancellations) {
            Long doctorId = cancelled.getDoctor().getId();
            LocalDate date = cancelled.getScheduledAt().toLocalDate();

            List<WaitlistEntry> eligible = waitlistRepository.findEligibleForPromotion(doctorId, date);
            if (eligible.isEmpty()) continue;

            WaitlistEntry first = eligible.get(0);

            if (appointmentRepository.existsConflict(doctorId, cancelled.getScheduledAt(), cancelled.getEndTime())) {
                continue;
            }

            Appointment promoted_appt = Appointment.builder()
                    .patient(first.getPatient())
                    .doctor(cancelled.getDoctor())
                    .department(cancelled.getDepartment())
                    .scheduledAt(cancelled.getScheduledAt())
                    .endTime(cancelled.getEndTime())
                    .durationMins(cancelled.getDurationMins())
                    .type(cancelled.getType())
                    .status(AppointmentStatus.PENDING)
                    .confirmationCode("MB-WL-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase())
                    .build();

            Appointment savedAppt = appointmentRepository.save(promoted_appt);

            first.setStatus("PROMOTED");
            first.setPromotedAt(LocalDateTime.now());
            first.setPromotedAppointment(savedAppt);
            waitlistRepository.save(first);

            eventProducer.publish("WAITLIST", String.valueOf(first.getId()), "PROMOTED",
                    KafkaTopics.WAITLIST_EVENTS,
                    WaitlistEvent.builder()
                            .eventId(UUID.randomUUID().toString())
                            .eventType("PROMOTED")
                            .waitlistId(first.getId())
                            .patientId(first.getPatient().getId())
                            .doctorId(cancelled.getDoctor().getId())
                            .appointmentId(savedAppt.getId())
                            .doctorName(cancelled.getDoctor().getUser().getFullName())
                            .scheduledAt(cancelled.getScheduledAt())
                            .occurredAt(LocalDateTime.now())
                            .build());

            promoted++;
            log.info("Waitlist entry [{}] promoted: patient [{}] assigned to freed slot for doctor [{}] at [{}]",
                    first.getId(), first.getPatient().getId(), doctorId, cancelled.getScheduledAt());
        }

        if (promoted > 0) {
            log.info("Waitlist promotion job: {} entries promoted", promoted);
        }
    }
}
