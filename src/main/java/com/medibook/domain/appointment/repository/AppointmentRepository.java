package com.medibook.domain.appointment.repository;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    Page<Appointment> findByPatientId(Long patientId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    Page<Appointment> findByPatientIdAndScheduledAtAfterOrderByScheduledAtAsc(Long patientId, LocalDateTime time, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "doctor", "doctor.user", "doctor.department"})
    Page<Appointment> findByPatientIdAndScheduledAtBeforeOrderByScheduledAtDesc(Long patientId, LocalDateTime time, Pageable pageable);

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
}
