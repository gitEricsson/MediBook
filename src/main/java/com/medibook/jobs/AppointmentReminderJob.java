package com.medibook.jobs;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import com.medibook.messaging.repository.ProcessedEventRepository;
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
 * Scans CONFIRMED appointments and publishes reminder events at three horizons:
 * 48 h, 24 h, and 2 h before the scheduled time.
 *
 * Deduplication strategy: each reminder carries a deterministic eventId of the form
 * "reminder-{hours}h-{appointmentId}". The Kafka consumer persists processed eventIds in
 * ProcessedEventRepository, so a redelivery or a job-retry inside the same window is
 * silently swallowed at the consumer without sending a duplicate notification.
 *
 * Runs every 30 minutes so the 2 h window (which spans only ~60 min of clock time)
 * is reliably caught.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AppointmentReminderJob {

    private static final int[] REMINDER_HOURS = {48, 24, 2};

    private final AppointmentRepository appointmentRepository;
    private final AppointmentEventProducer eventProducer;
    private final ProcessedEventRepository processedEventRepository;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedRate = 1_800_000, initialDelay = 60_000)  // every 30 minutes
    @SchedulerLock(name = "AppointmentReminderJob_sendReminders", lockAtMostFor = "25m", lockAtLeastFor = "1m")
    public void sendReminders() {
        try {
            sendRemindersImpl();
        } catch (Exception ex) {
            log.error("AppointmentReminderJob failed", ex);
            meterRegistry.counter("scheduled.job.failure", "job", "AppointmentReminderJob").increment();
            try {
                Thread.sleep(5_000);
                sendRemindersImpl();
                log.info("AppointmentReminderJob retry succeeded");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("AppointmentReminderJob retry interrupted", ie);
            } catch (Exception retryEx) {
                log.error("AppointmentReminderJob retry also failed", retryEx);
                meterRegistry.counter("scheduled.job.failure", "job", "AppointmentReminderJob").increment();
            }
        }
    }

    private void sendRemindersImpl() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        for (int hours : REMINDER_HOURS) {
            // Query a ±30 min band around the target horizon so each half-hourly run
            // catches appointments that fall in that window without double-firing.
            LocalDateTime from = now.plusHours(hours).minusMinutes(30);
            LocalDateTime to   = now.plusHours(hours).plusMinutes(30);

            List<Appointment> upcoming = appointmentRepository.findUpcomingConfirmed(from, to);
            log.info("ReminderJob [{}h]: found {} appointments", hours, upcoming.size());

            for (Appointment appt : upcoming) {
                String eventId = String.format("reminder-%dh-%d", hours, appt.getId());

                // Skip if already published — guards against job retries and Kafka redeliveries.
                if (processedEventRepository.existsById(eventId)) {
                    log.debug("Skipping already-sent reminder {} for appointment {}", hours, appt.getId());
                    continue;
                }

                AppointmentEvent event = AppointmentEvent.builder()
                        .eventId(eventId)
                        .eventType("REMINDER")
                        .appointmentId(appt.getId())
                        .patientId(appt.getPatient().getId())
                        .patientEmail(appt.getPatient().getEmail())
                        .patientName(appt.getPatient().getFullName())
                        .doctorId(appt.getDoctor().getId())
                        .doctorName(appt.getDoctor().getUser().getFullName())
                        .doctorEmail(appt.getDoctor().getUser().getEmail())
                        .scheduledAt(appt.getScheduledAt())
                        .status(appt.getStatus())
                        .hoursBeforeAppointment(hours)
                        .build();

                eventProducer.publishAppointmentEvent(event);
                log.info("Published {}h reminder for appointment {}", hours, appt.getId());
                meterRegistry.counter("reminder.published", "hours", String.valueOf(hours)).increment();
            }
        }
    }
}
