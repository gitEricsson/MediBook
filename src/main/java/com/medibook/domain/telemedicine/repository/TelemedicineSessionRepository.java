package com.medibook.domain.telemedicine.repository;

import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TelemedicineSessionRepository extends JpaRepository<TelemedicineSession, Long> {

    Optional<TelemedicineSession> findByAppointmentId(Long appointmentId);

    Optional<TelemedicineSession> findFirstByAppointmentIdAndStatusIn(
            Long appointmentId,
            Collection<TelemedicineSessionStatus> statuses);

    @Query("""
        SELECT s FROM TelemedicineSession s
        JOIN FETCH s.appointment a
        JOIN FETCH a.patient
        JOIN FETCH a.doctor d
        JOIN FETCH d.user
        WHERE s.id = :id
        """)
    Optional<TelemedicineSession> findByIdWithDetails(Long id);

    /**
     * CREATED / RINGING / WAITING sessions whose startedAt is before the stale cutoff —
     * fed to StaleTelemedicineSessionJob for orphan cleanup.
     */
    @Query("""
        SELECT s FROM TelemedicineSession s
        JOIN FETCH s.appointment
        WHERE s.status IN (
            com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus.CREATED,
            com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus.RINGING,
            com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus.WAITING
        )
          AND s.startedAt < :cutoff
        """)
    List<TelemedicineSession> findStaleActiveSessions(LocalDateTime cutoff);

    /**
     * ACTIVE sessions where the appointment's window has fully expired (endTime + post-window).
     * Fed to StaleTelemedicineSessionJob for forced session close.
     */
    @Query("""
        SELECT s FROM TelemedicineSession s
        JOIN FETCH s.appointment a
        WHERE s.status = com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus.ACTIVE
          AND (
            (a.endTime IS NOT NULL AND a.endTime < :windowCutoff)
            OR (a.endTime IS NULL AND a.scheduledAt < :windowCutoff)
          )
        """)
    List<TelemedicineSession> findExpiredActiveSessions(LocalDateTime windowCutoff);
}
