package com.medibook.jobs;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scans CONFIRMED appointments in the next 24 hours and publishes reminder events.
 * Runs every hour.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AppointmentReminderJob {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentEventProducer eventProducer;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedRate = 3_600_000, initialDelay = 60_000)  // every hour
    @SchedulerLock(name = "AppointmentReminderJob_sendReminders", lockAtMostFor = "50m", lockAtLeastFor = "1m")
    public void sendReminders() {
        try {
            sendRemindersImpl();
        } catch (Exception ex) {
            log.error("AppointmentReminderJob failed with error", ex);
            meterRegistry.counter("scheduled.job.failure", "job", "AppointmentReminderJob").increment();
            // Retry once after 5 seconds for critical job
            try {
                Thread.sleep(5_000);
                sendRemindersImpl();
                log.info("AppointmentReminderJob retry succeeded");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("AppointmentReminderJob retry interrupted", ie);
            } catch (Exception retryEx) {
                log.error("AppointmentReminderJob retry failed", retryEx);
                meterRegistry.counter("scheduled.job.failure", "job", "AppointmentReminderJob").increment();
            }
        }
    }

    private void sendRemindersImpl() {
        LocalDateTime now  = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = now.plusHours(23);
        LocalDateTime to   = now.plusHours(24);

        List<Appointment> upcoming = appointmentRepository.findUpcomingConfirmed(from, to);
        log.info("ReminderJob: found {} upcoming appointments", upcoming.size());

        upcoming.forEach(appt -> {
            AppointmentEvent event = AppointmentEvent.builder()
                    .eventType("REMINDER")
                    .appointmentId(appt.getId())
                    .patientId(appt.getPatient().getId())
                    .patientEmail(appt.getPatient().getEmail())
                    .patientName(appt.getPatient().getFullName())
                    .doctorId(appt.getDoctor().getId())
                    .doctorName(appt.getDoctor().getUser().getFullName())
                    .scheduledAt(appt.getScheduledAt())
                    .status(appt.getStatus())
                    .build();
            eventProducer.publishAppointmentEvent(event);
        });
    }
}
