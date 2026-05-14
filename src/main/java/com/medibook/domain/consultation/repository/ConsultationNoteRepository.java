package com.medibook.domain.consultation.repository;

import com.medibook.domain.consultation.entity.ConsultationNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface ConsultationNoteRepository extends JpaRepository<ConsultationNote, Long> {

    Optional<ConsultationNote> findByAppointmentId(Long appointmentId);

    @Query("""
           SELECT cn FROM ConsultationNote cn
           JOIN FETCH cn.appointment a
           JOIN FETCH a.patient
           JOIN FETCH a.doctor d
           JOIN FETCH d.user
           WHERE a.patient.id = :patientId
           ORDER BY cn.createdAt DESC
           """)
    List<ConsultationNote> findByPatientId(Long patientId);

    // Soft delete methods (admin only)

    /**
     * Find consultation note by ID including soft-deleted records (admin only).
     */
    @Query(value = "SELECT * FROM consultation_notes WHERE id = :id", nativeQuery = true)
    Optional<ConsultationNote> findByIdIncludeDeleted(@Param("id") Long id);

    /**
     * Find deleted consultation notes in a date range for audit/recovery.
     */
    @Query(value = "SELECT * FROM consultation_notes WHERE deleted_at IS NOT NULL AND deleted_at BETWEEN :from AND :to ORDER BY deleted_at DESC", nativeQuery = true)
    List<ConsultationNote> findDeletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Count soft-deleted consultation notes.
     */
    @Query(value = "SELECT COUNT(*) FROM consultation_notes WHERE deleted_at IS NOT NULL", nativeQuery = true)
    long countDeleted();

    /**
     * Find notes for a patient created before a specific cutoff (for doctor access-grant limiting).
     */
    @Query("""
           SELECT cn FROM ConsultationNote cn
           JOIN FETCH cn.appointment a
           JOIN FETCH a.patient
           JOIN FETCH a.doctor d
           JOIN FETCH d.user
           WHERE a.patient.id = :patientId
           AND cn.createdAt <= :cutoff
           ORDER BY cn.createdAt DESC
           """)
    List<ConsultationNote> findByPatientIdAndCreatedAtBefore(
            @Param("patientId") Long patientId,
            @Param("cutoff") LocalDateTime cutoff);
}
