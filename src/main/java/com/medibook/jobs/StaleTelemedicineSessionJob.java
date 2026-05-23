package com.medibook.jobs;

import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import com.medibook.domain.telemedicine.repository.TelemedicineSessionRepository;
import com.medibook.domain.telemedicine.service.TwilioVideoService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cleans up orphaned telemedicine sessions that were never properly closed.
 *
 * Pass 1 — Stale pre-join sessions: CREATED / RINGING / WAITING sessions older
 *   than {@code app.telemedicine.stale-session-minutes} (default 30 min) are
 *   force-ended.  These represent calls that were initiated but never joined.
 *
 * Pass 2 — Expired active sessions: ACTIVE sessions whose appointment window
 *   (endTime + {@code app.telemedicine.post-window-minutes} post-window) has passed
 *   are force-ended.  This guards against calls that lost connectivity or were
 *   abandoned without an explicit endCall.
 *
 * Both passes complete the Twilio room so the session does not accumulate against
 * Twilio's concurrent room limit.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class StaleTelemedicineSessionJob {

    private final TelemedicineSessionRepository sessionRepository;
    private final TwilioVideoService            twilioVideoService;
    private final MeterRegistry                 meterRegistry;

    @Value("${app.telemedicine.stale-session-minutes:30}")
    private int staleSessionMinutes;

    /** Must match TelemedicineCallService.WINDOW_POST_MINUTES */
    @Value("${app.telemedicine.post-window-minutes:10}")
    private int postWindowMinutes;

    /** Runs every 5 minutes. ShedLock ensures single execution across replicas. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    @SchedulerLock(name = "StaleTelemedicineSessionJob_run", lockAtMostFor = "5m", lockAtLeastFor = "30s")
    public void run() {
        try {
            closeStaleSessions();
            closeExpiredActiveSessions();
        } catch (Exception ex) {
            log.error("StaleTelemedicineSessionJob failed", ex);
            meterRegistry.counter("scheduled.job.failure", "job", "StaleTelemedicineSessionJob").increment();
        }
    }

    @Transactional
    public void closeStaleSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleSessionMinutes);
        List<TelemedicineSession> stale = sessionRepository.findStaleActiveSessions(cutoff);
        if (stale.isEmpty()) return;

        log.info("StaleTelemedicineSessionJob: force-ending {} stale pre-join session(s) (cutoff={})",
                stale.size(), cutoff);

        for (TelemedicineSession session : stale) {
            closeSession(session, "stale-auto-closed: no participant joined within " + staleSessionMinutes + " min");
        }
    }

    @Transactional
    public void closeExpiredActiveSessions() {
        LocalDateTime windowCutoff = LocalDateTime.now().minusMinutes(postWindowMinutes);
        List<TelemedicineSession> expired = sessionRepository.findExpiredActiveSessions(windowCutoff);
        if (expired.isEmpty()) return;

        log.info("StaleTelemedicineSessionJob: force-ending {} expired active session(s) (window cutoff={})",
                expired.size(), windowCutoff);

        for (TelemedicineSession session : expired) {
            closeSession(session, "auto-closed: appointment window expired");
        }
    }

    private void closeSession(TelemedicineSession session, String reason) {
        try {
            LocalDateTime endedAt = LocalDateTime.now();
            session.setStatus(TelemedicineSessionStatus.ENDED);
            session.setEndedAt(endedAt);
            session.setEndReason(reason);
            if (session.getStartedAt() != null) {
                session.setDurationSeconds((int) Duration.between(session.getStartedAt(), endedAt).getSeconds());
            }
            sessionRepository.save(session);

            // Complete the Twilio room to release the concurrent-room slot.
            String roomRef = session.getTwilioRoomSid() != null
                    ? session.getTwilioRoomSid() : session.getTwilioRoomName();
            if (roomRef != null && !roomRef.isBlank()) {
                try {
                    twilioVideoService.completeRoom(roomRef);
                } catch (Exception ex) {
                    log.warn("StaleTelemedicineSessionJob: could not complete Twilio room [{}]: {}",
                            roomRef, ex.getMessage());
                }
            }

            log.info("StaleTelemedicineSessionJob: closed session [{}] — {}", session.getId(), reason);
            meterRegistry.counter("scheduled.job.telemedicine.session_auto_closed").increment();
        } catch (Exception ex) {
            log.warn("StaleTelemedicineSessionJob: failed to close session [{}]: {}",
                    session.getId(), ex.getMessage());
        }
    }
}
