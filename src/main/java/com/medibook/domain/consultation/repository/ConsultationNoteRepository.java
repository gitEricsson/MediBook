package com.medibook.domain.consultation.repository;

import com.medibook.domain.consultation.entity.ConsultationNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
