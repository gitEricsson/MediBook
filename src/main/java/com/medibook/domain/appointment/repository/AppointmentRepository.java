package com.medibook.domain.appointment.repository;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    Page<Appointment> findByPatientId(Long patientId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    @Query("""
           SELECT a FROM Appointment a
           WHERE a.patient.id = :patientId
           AND a.scheduledAt > :time
           ORDER BY a.scheduledAt ASC
           """)
    Page<Appointment> findByPatientIdAndScheduledAtAfterOrderByScheduledAtAsc(Long patientId, LocalDateTime time, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    @Query("""
           SELECT a FROM Appointment a
           WHERE a.patient.id = :patientId
           AND a.scheduledAt < :time
           ORDER BY a.scheduledAt DESC
           """)
    Page<Appointment> findByPatientIdAndScheduledAtBeforeOrderByScheduledAtDesc(Long patientId, LocalDateTime time, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    @Query("""
           SELECT a FROM Appointment a
           WHERE a.patient.id = :patientId
           AND a.scheduledAt >= :anchor
           AND (
                :cursorScheduledAt IS NULL
                OR a.scheduledAt > :cursorScheduledAt
                OR (a.scheduledAt = :cursorScheduledAt AND a.id > :cursorId)
           )
           ORDER BY a.scheduledAt ASC, a.id ASC
           """)
    List<Appointment> findUpcomingByPatientCursor(
            Long patientId,
            LocalDateTime anchor,
            LocalDateTime cursorScheduledAt,
            Long cursorId,
            Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    @Query("""
           SELECT a FROM Appointment a
           WHERE a.patient.id = :patientId
           AND a.scheduledAt < :anchor
           AND (
                :cursorScheduledAt IS NULL
                OR a.scheduledAt < :cursorScheduledAt
                OR (a.scheduledAt = :cursorScheduledAt AND a.id < :cursorId)
           )
           ORDER BY a.scheduledAt DESC, a.id DESC
           """)
    List<Appointment> findPastByPatientCursor(
            Long patientId,
            LocalDateTime anchor,
            LocalDateTime cursorScheduledAt,
            Long cursorId,
            Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    Page<Appointment> findByDoctorId(Long doctorId, Pageable pageable);

    Page<Appointment> findByStatus(AppointmentStatus status, Pageable pageable);

    @Query("""
           SELECT a FROM Appointment a
           JOIN FETCH a.patient
           JOIN FETCH a.doctor d
           JOIN FETCH d.user
           JOIN FETCH d.department
           WHERE a.id = :id
           """)
    Optional<Appointment> findByIdWithDetails(Long id);

    @Query("""
           SELECT COUNT(a) > 0 FROM Appointment a
           WHERE a.doctor.user.id = :doctorUserId
           AND a.patient.id = :patientId
           """)
    boolean existsDoctorPatientRelationship(Long doctorUserId, Long patientId);

    /** Used by reminder job — upcoming appointments in the next window */
    @Query("""
           SELECT a FROM Appointment a
           JOIN FETCH a.patient
           JOIN FETCH a.doctor d
           JOIN FETCH d.user
           JOIN FETCH d.department
           WHERE a.scheduledAt BETWEEN :from AND :to
           AND a.status IN ('CONFIRMED', 'CHECKED_IN', 'IN_WAITING_ROOM')
           """)
    List<Appointment> findUpcomingConfirmed(LocalDateTime from, LocalDateTime to);

    /** Conflict check — overlapping time range for the same doctor */
    @Query("""
           SELECT COUNT(a) > 0 FROM Appointment a
           WHERE a.doctor.id = :doctorId
           AND a.scheduledAt < :endTime
           AND a.endTime > :scheduledAt
           AND a.status NOT IN ('CANCELLED', 'NO_SHOW', 'REFUNDED')
           """)
    boolean existsConflict(Long doctorId, LocalDateTime scheduledAt, LocalDateTime endTime);

    @Query("""
           SELECT COUNT(a) > 0 FROM Appointment a
           WHERE a.id <> :appointmentId
           AND a.doctor.id = :doctorId
           AND a.scheduledAt < :endTime
           AND a.endTime > :scheduledAt
           AND a.status NOT IN ('CANCELLED', 'NO_SHOW', 'REFUNDED')
           """)
    boolean existsConflictExcluding(Long appointmentId, Long doctorId, LocalDateTime scheduledAt, LocalDateTime endTime);


    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    List<Appointment> findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(Long doctorId, LocalDateTime startOfDay, LocalDateTime endOfDay);

    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.doctor.id = :doctorId
        AND a.scheduledAt BETWEEN :startOfDay AND :endOfDay
        AND a.status = :status
    """)
    long countByDoctorIdAndDateAndStatus(Long doctorId, LocalDateTime startOfDay, LocalDateTime endOfDay, AppointmentStatus status);

    /**
     * Return active appointment counts per doctor for a given day, in a single query.
     * Used by emergency doctor assignment to avoid N+1.
     * Counts IN_CONSULTATION + CONFIRMED + EMERGENCY_PENDING_SETTLEMENT so that a doctor
     * just assigned to an emergency is not immediately eligible as the "least loaded" again.
     */
    @Query("""
        SELECT a.doctor.id, COUNT(a) FROM Appointment a
        WHERE a.doctor.id IN :doctorIds
        AND a.scheduledAt BETWEEN :startOfDay AND :endOfDay
        AND a.status IN ('IN_CONSULTATION', 'CONFIRMED', 'EMERGENCY_PENDING_SETTLEMENT')
        GROUP BY a.doctor.id
    """)
    List<Object[]> countActiveAppointmentsByDoctorIds(
            @Param("doctorIds") List<Long> doctorIds,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("endOfDay") LocalDateTime endOfDay);

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    Optional<Appointment> findFirstByDoctorIdAndScheduledAtAfterAndStatusOrderByScheduledAtAsc(Long doctorId, LocalDateTime now, AppointmentStatus status);

    boolean existsByConfirmationCode(String confirmationCode);

    /**
     * Check whether patient has an unresolved emergency balance or active
     * emergency consultation. COMPLETED is intentionally excluded — under the
     * new FSM an emergency reaches COMPLETED only after settlement, so any
     * appointment in that state is fully paid. A successful payment also wins
     * over a stale EMERGENCY_PENDING_SETTLEMENT row so old data inconsistencies
     * recover automatically. We also match on type OR consultationType so legacy
     * rows that only set one of the two are still caught.
     */
    @Query("""
           SELECT COUNT(a) > 0 FROM Appointment a
           WHERE a.patient.id = :patientId
           AND (a.type = com.medibook.domain.appointment.entity.AppointmentType.EMERGENCY
                OR a.consultationType = com.medibook.domain.appointment.entity.AppointmentType.EMERGENCY)
           AND a.status IN (
               com.medibook.domain.appointment.entity.AppointmentStatus.IN_CONSULTATION,
               com.medibook.domain.appointment.entity.AppointmentStatus.EMERGENCY_PENDING_SETTLEMENT
           )
           AND NOT EXISTS (
               SELECT p.id FROM Payment p
               WHERE p.appointment.id = a.id
               AND p.status = com.medibook.domain.payment.entity.PaymentStatus.SUCCESSFUL
           )
           """)
    boolean existsUnresolvedEmergencyDebt(@Param("patientId") Long patientId);

    /** PENDING appointments older than the cutoff — fed to StalePendingCancelJob. */
    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    @Query("""
           SELECT a FROM Appointment a
           WHERE a.status IN (
               com.medibook.domain.appointment.entity.AppointmentStatus.PENDING,
               com.medibook.domain.appointment.entity.AppointmentStatus.PENDING_PAYMENT
           )
             AND a.createdAt < :cutoff
           """)
    List<Appointment> findStalePending(LocalDateTime cutoff);

    /**
     * CONFIRMED / IN_WAITING_ROOM appointments whose scheduled start is before the NO_SHOW cutoff.
     * These are fed to AppointmentLifecycleJob to be auto-marked as NO_SHOW.
     */
    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    @Query("""
           SELECT a FROM Appointment a
           WHERE a.status IN (
               com.medibook.domain.appointment.entity.AppointmentStatus.CONFIRMED,
               com.medibook.domain.appointment.entity.AppointmentStatus.IN_WAITING_ROOM
           )
             AND a.endTime < :cutoff
           """)
    List<Appointment> findMissedAppointments(LocalDateTime cutoff);

    /**
     * IN_CONSULTATION appointments whose endTime has passed the overtime cutoff.
     * These are fed to AppointmentLifecycleJob to be auto-completed.
     */
    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    @Query("""
           SELECT a FROM Appointment a
           WHERE a.status = com.medibook.domain.appointment.entity.AppointmentStatus.IN_CONSULTATION
             AND a.endTime < :cutoff
           """)
    List<Appointment> findOvertimeConsultations(LocalDateTime cutoff);

    /** PENDING appointments due to auto-cancel in the soon-window — for the reminder job. */
    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    @Query("""
           SELECT a FROM Appointment a
           WHERE a.status IN (
               com.medibook.domain.appointment.entity.AppointmentStatus.PENDING,
               com.medibook.domain.appointment.entity.AppointmentStatus.PENDING_PAYMENT
           )
             AND a.createdAt BETWEEN :reminderFrom AND :reminderTo
           """)
    List<Appointment> findPendingDueForReminder(LocalDateTime reminderFrom, LocalDateTime reminderTo);

    boolean existsByDoctorIdAndScheduledAt(Long doctorId, java.time.LocalDateTime scheduledAt);

    Optional<Appointment> findByConfirmationCode(String confirmationCode);

    @Query("""
        SELECT a.status, COUNT(a), SUM(0) FROM Appointment a
        WHERE a.scheduledAt BETWEEN :from AND :to
        GROUP BY a.status
        """)
    List<Object[]> countByStatusBetween(LocalDateTime from, LocalDateTime to);

    @Query("""
        SELECT d.name, COUNT(a) FROM Appointment a
        JOIN a.doctor doc JOIN doc.department d
        WHERE a.scheduledAt BETWEEN :from AND :to
        GROUP BY d.name
        """)
    List<Object[]> countByDepartmentBetween(LocalDateTime from, LocalDateTime to);

    @Query("""
        SELECT a.type, COUNT(a) FROM Appointment a
        WHERE a.scheduledAt BETWEEN :from AND :to
        GROUP BY a.type
        """)
    List<Object[]> countByTypeBetween(LocalDateTime from, LocalDateTime to);

    @Query("""
        SELECT doc.id, CONCAT(u.firstName, ' ', u.lastName), COUNT(a),
               SUM(CASE WHEN a.status = 'COMPLETED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN a.status = 'CANCELLED' THEN 1 ELSE 0 END),
               doc.averageRating
        FROM Appointment a
        JOIN a.doctor doc JOIN doc.user u
        WHERE a.scheduledAt BETWEEN :from AND :to
        GROUP BY doc.id, u.firstName, u.lastName, doc.averageRating
        ORDER BY COUNT(a) DESC
        """)
    List<Object[]> getDoctorUtilizationStats(LocalDateTime from, LocalDateTime to);

    @Query("""
        SELECT dept.id, dept.name,
               COUNT(a),
               SUM(CASE WHEN a.status = 'COMPLETED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN a.status = 'CANCELLED' THEN 1 ELSE 0 END)
        FROM Appointment a
        JOIN a.doctor doc JOIN doc.department dept
        WHERE a.scheduledAt BETWEEN :from AND :to
        GROUP BY dept.id, dept.name
        ORDER BY COUNT(a) DESC
        """)
    List<Object[]> getDepartmentCapacityStats(LocalDateTime from, LocalDateTime to);

    // Soft delete methods (admin only)

    /**
     * Find appointment by ID including soft-deleted records (admin only).
     */
    @Query(value = "SELECT * FROM appointments WHERE id = :id", nativeQuery = true)
    Optional<Appointment> findByIdIncludeDeleted(@Param("id") Long id);

    /**
     * Find deleted appointments in a date range for audit/recovery.
     */
    @Query(value = "SELECT * FROM appointments WHERE deleted_at IS NOT NULL AND deleted_at BETWEEN :from AND :to ORDER BY deleted_at DESC", nativeQuery = true)
    List<Appointment> findDeletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Count soft-deleted appointments.
     */
    @Query(value = "SELECT COUNT(*) FROM appointments WHERE deleted_at IS NOT NULL", nativeQuery = true)
    long countDeleted();
}
