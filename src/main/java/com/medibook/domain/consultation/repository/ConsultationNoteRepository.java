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
    @Query("""
        SELECT cn FROM ConsultationNote cn
        WHERE cn.id = :id
        """)
    Optional<ConsultationNote> findByIdIncludeDeleted(@Param("id") Long id);

    /**
     * Find deleted consultation notes in a date range for audit/recovery.
     */
    @Query("""
        SELECT cn FROM ConsultationNote cn
        WHERE cn.deletedAt IS NOT NULL
        AND cn.deletedAt BETWEEN :from AND :to
        ORDER BY cn.deletedAt DESC
        """)
    List<ConsultationNote> findDeletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Count soft-deleted consultation notes.
     */
    @Query("""
        SELECT COUNT(cn) FROM ConsultationNote cn
        WHERE cn.deletedAt IS NOT NULL
        """)
    long countDeleted();
}
