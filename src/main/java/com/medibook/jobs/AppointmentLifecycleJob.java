package com.medibook.jobs;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.appointment.service.AppointmentTransitionService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Drives automatic appointment lifecycle transitions that cannot be triggered by
 * patient/doctor action alone.
 *
 * Pass 1 — NO_SHOW: CONFIRMED and IN_WAITING_ROOM appointments whose scheduled
 *   start passed more than {@code app.appointments.no-show-grace-minutes} ago are
 *   auto-marked NO_SHOW.  Grace period (default 30 min) gives late patients a window
 *   to still check in before the slot is closed.
 *
 * Pass 2 — COMPLETE: IN_CONSULTATION appointments whose endTime passed more than
 *   {@code app.appointments.overtime-buffer-minutes} ago are auto-completed.
 *   Buffer (default 15 min) absorbs minor overruns without interrupting ongoing calls.
 *
 * Both passes publish standard AppointmentEvents so downstream notification +
 * audit pipelines fire uniformly with manual transitions.
 *
 * ShedLock prevents duplicate execution when multiple replicas are running.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AppointmentLifecycleJob {

    private final AppointmentRepository        appointmentRepository;
    private final AppointmentTransitionService transitionService;
    private final MeterRegistry               meterRegistry;

    @Value("${app.appointments.no-show-grace-minutes:30}")
    private int noShowGraceMinutes;

    @Value("${app.appointments.overtime-buffer-minutes:15}")
    private int overtimeBufferMinutes;

    /** Runs every 5 minutes. ShedLock ensures single execution across replicas. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @SchedulerLock(name = "AppointmentLifecycleJob_run", lockAtMostFor = "5m", lockAtLeastFor = "30s")
    public void run() {
        try {
            markNoShows();
            autoCompleteConsultations();
        } catch (Exception ex) {
            log.error("AppointmentLifecycleJob failed", ex);
            meterRegistry.counter("scheduled.job.failure", "job", "AppointmentLifecycleJob").increment();
        }
    }

    /**
     * Auto-marks CONFIRMED / IN_WAITING_ROOM appointments as NO_SHOW when the grace
     * period after their scheduled start has elapsed.
     */
    private void markNoShows() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(noShowGraceMinutes);
        List<Appointment> missed = appointmentRepository.findMissedAppointments(cutoff);
        if (missed.isEmpty()) return;

        log.info("AppointmentLifecycleJob: marking {} appointment(s) as NO_SHOW (cutoff={})",
                missed.size(), cutoff);

        for (Appointment a : missed) {
            try {
                transitionService.systemTransition(a.getId(), AppointmentStatus.NO_SHOW,
                        "Auto-marked NO_SHOW after " + noShowGraceMinutes + "-minute grace period");
                meterRegistry.counter("scheduled.job.appointment.auto_no_show").increment();
            } catch (Exception ex) {
                log.warn("AppointmentLifecycleJob: could not mark appt [{}] as NO_SHOW: {}",
                        a.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Auto-completes IN_CONSULTATION appointments whose endTime passed more than
     * {@code overtimeBufferMinutes} ago, guarding against sessions that were never
     * manually closed by the doctor.
     */
    private void autoCompleteConsultations() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(overtimeBufferMinutes);
        List<Appointment> overtime = appointmentRepository.findOvertimeConsultations(cutoff);
        if (overtime.isEmpty()) return;

        log.info("AppointmentLifecycleJob: auto-completing {} overtime consultation(s) (cutoff={})",
                overtime.size(), cutoff);

        for (Appointment a : overtime) {
            try {
                transitionService.systemTransition(a.getId(), AppointmentStatus.COMPLETED,
                        "Auto-completed: consultation exceeded scheduled end time by "
                                + overtimeBufferMinutes + "+ minutes");
                meterRegistry.counter("scheduled.job.appointment.auto_completed").increment();
            } catch (Exception ex) {
                log.warn("AppointmentLifecycleJob: could not auto-complete appt [{}]: {}",
                        a.getId(), ex.getMessage());
            }
        }
    }
}
