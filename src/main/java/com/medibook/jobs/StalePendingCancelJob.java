package com.medibook.jobs;

import com.medibook.common.mail.TransactionalEmailService;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Auto-cancels PENDING (unpaid) appointments older than {@code app.appointments.pending-ttl-minutes}.
 *
 * Without this job, a patient who taps "Pay later" and never returns would hold the
 * doctor's slot in the DB forever, blocking other patients from booking it.
 *
 * Also runs a reminder pass: PENDING appointments {@code remind-before-minutes} from
 * the auto-cancel cutoff get a "your slot is about to be released" email so the
 * patient has one last chance to come back and finish payment.
 *
 * Both passes publish standard {@link AppointmentEvent}s through the Kafka outbox so
 * existing in-app notifications + STOMP push + email pipeline all fire uniformly.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class StalePendingCancelJob {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentEventProducer eventProducer;
    private final TransactionalEmailService transactionalEmailService;
    private final MeterRegistry meterRegistry;

    @Value("${app.appointments.pending-ttl-minutes:30}")
    private int pendingTtlMinutes;

    @Value("${app.appointments.pending-reminder-before-minutes:5}")
    private int reminderBeforeMinutes;

    /**
     * Runs every minute. Short cadence so a 30-min TTL flips within ~1 min of expiry,
     * keeping the slot release responsive without piling up work. SchedulerLock
     * prevents duplicate execution across replicas.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "StalePendingCancelJob_run", lockAtMostFor = "5m", lockAtLeastFor = "10s")
    public void run() {
        try {
            sendReminders();
            cancelExpired();
        } catch (Exception ex) {
            log.error("StalePendingCancelJob failed", ex);
            meterRegistry.counter("scheduled.job.failure", "job", "StalePendingCancelJob").increment();
        }
    }

    /**
     * Phase 3 — soft reminder. PENDING appointments whose auto-cancel window is N min
     * away get a heads-up email. The window is `[ttl - remindBefore - 1min, ttl - remindBefore]`
     * relative to createdAt so each appointment receives exactly one reminder.
     */
    private void sendReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime reminderFrom = now.minusMinutes(pendingTtlMinutes - reminderBeforeMinutes);
        LocalDateTime reminderTo   = now.minusMinutes(pendingTtlMinutes - reminderBeforeMinutes - 1);
        if (!reminderFrom.isBefore(reminderTo)) return;

        List<Appointment> due = appointmentRepository.findPendingDueForReminder(reminderTo, reminderFrom);
        if (due.isEmpty()) return;

        log.info("StalePendingCancelJob: sending payment reminders for {} appointments", due.size());
        for (Appointment a : due) {
            try {
                String email = a.getPatient() != null ? a.getPatient().getEmail() : null;
                if (email == null || email.isBlank()) continue;
                transactionalEmailService.sendHtml(email,
                        "Your MediBook slot is about to be released",
                        reminderEmailBody(a));
            } catch (Exception ex) {
                log.warn("Failed to send pending-payment reminder for appt {}: {}", a.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Phase 2 — flip PENDING → CANCELLED past the TTL. Publishes a CANCELLED event so
     * the existing NotificationService fires patient + doctor in-app + email
     * notifications uniformly with manual cancellations.
     */
    @Transactional
    public void cancelExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(pendingTtlMinutes);
        List<Appointment> stale = appointmentRepository.findStalePending(cutoff);
        if (stale.isEmpty()) return;

        log.info("StalePendingCancelJob: auto-cancelling {} stale PENDING appointments (cutoff={})",
                stale.size(), cutoff);

        for (Appointment a : stale) {
            a.setStatus(AppointmentStatus.CANCELLED);
            appointmentRepository.save(a);

            AppointmentEvent event = AppointmentEvent.builder()
                    .eventType("CANCELLED")
                    .appointmentId(a.getId())
                    .patientId(a.getPatient() != null ? a.getPatient().getId() : null)
                    .patientEmail(a.getPatient() != null ? a.getPatient().getEmail() : null)
                    .patientName(a.getPatient() != null ? a.getPatient().getFullName() : null)
                    .doctorId(a.getDoctor() != null && a.getDoctor().getUser() != null
                            ? a.getDoctor().getUser().getId() : null)
                    .doctorEmail(a.getDoctor() != null && a.getDoctor().getUser() != null
                            ? a.getDoctor().getUser().getEmail() : null)
                    .doctorName(a.getDoctor() != null && a.getDoctor().getUser() != null
                            ? a.getDoctor().getUser().getFullName() : null)
                    .scheduledAt(a.getScheduledAt())
                    .status(AppointmentStatus.CANCELLED)
                    .build();
            try {
                eventProducer.publishAppointmentEvent(event);
            } catch (Exception ex) {
                log.warn("Failed to publish CANCELLED event for auto-cancelled appt {}: {}",
                        a.getId(), ex.getMessage());
            }
            meterRegistry.counter("scheduled.job.appointment.auto_cancelled").increment();
        }
    }

    private String reminderEmailBody(Appointment a) {
        String when = a.getScheduledAt() == null ? "soon" : a.getScheduledAt().toString();
        String docName = a.getDoctor() != null && a.getDoctor().getUser() != null
                ? a.getDoctor().getUser().getFullName() : "your doctor";
        return "<html><body style=\"font-family:Helvetica,Arial,sans-serif;color:#1a1a1a;line-height:1.5;\">"
                + "<div style=\"max-width:560px;margin:0 auto;padding:24px;\">"
                + "<h2 style=\"color:#d97706;\">Your slot is about to be released</h2>"
                + "<p>Your appointment with <strong>Dr. " + esc(docName) + "</strong> on "
                + "<strong>" + esc(when) + "</strong> is still unpaid.</p>"
                + "<p>If you don't complete payment in the next " + reminderBeforeMinutes
                + " minute(s), the slot will be released for other patients.</p>"
                + "<p>Open MediBook → <em>My Visits</em> → <em>Complete payment</em>.</p>"
                + "<hr style=\"border:none;border-top:1px solid #eee;margin-top:24px;\"/>"
                + "<p style=\"font-size:11px;color:#999;\">Sent by MediBook · do not reply.</p>"
                + "</div></body></html>";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
