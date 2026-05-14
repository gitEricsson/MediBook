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
           AND a.status = 'CONFIRMED'
           """)
    List<Appointment> findUpcomingConfirmed(LocalDateTime from, LocalDateTime to);

    /** Conflict check — overlapping time range for the same doctor */
    @Query("""
           SELECT COUNT(a) > 0 FROM Appointment a
           WHERE a.doctor.id = :doctorId
           AND a.scheduledAt < :endTime
           AND a.endTime > :scheduledAt
           AND a.status NOT IN ('CANCELLED', 'NO_SHOW')
           """)
    boolean existsConflict(Long doctorId, LocalDateTime scheduledAt, LocalDateTime endTime);

    @Query("""
           SELECT COUNT(a) > 0 FROM Appointment a
           WHERE a.id <> :appointmentId
           AND a.doctor.id = :doctorId
           AND a.scheduledAt < :endTime
           AND a.endTime > :scheduledAt
           AND a.status NOT IN ('CANCELLED', 'NO_SHOW')
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

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    Optional<Appointment> findFirstByDoctorIdAndScheduledAtAfterAndStatusOrderByScheduledAtAsc(Long doctorId, LocalDateTime now, AppointmentStatus status);

    boolean existsByConfirmationCode(String confirmationCode);

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
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.id = :id
        """)
    Optional<Appointment> findByIdIncludeDeleted(@Param("id") Long id);

    /**
     * Find deleted appointments in a date range for audit/recovery.
     */
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.deletedAt IS NOT NULL
        AND a.deletedAt BETWEEN :from AND :to
        ORDER BY a.deletedAt DESC
        """)
    List<Appointment> findDeletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Count soft-deleted appointments.
     */
    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.deletedAt IS NOT NULL
        """)
    long countDeleted();
}
