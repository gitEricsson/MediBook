package com.medibook.domain.survey.repository;

import com.medibook.domain.survey.entity.ConsultationSurvey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ConsultationSurveyRepository extends JpaRepository<ConsultationSurvey, Long> {

    boolean existsByAppointmentId(Long appointmentId);

    Page<ConsultationSurvey> findByDoctorIdOrderBySubmittedAtDesc(Long doctorId, Pageable pageable);

    @Query("""
        SELECT s FROM ConsultationSurvey s
        JOIN FETCH s.appointment
        JOIN FETCH s.patient
        JOIN FETCH s.doctor d
        JOIN FETCH d.user
        ORDER BY s.submittedAt DESC
        """)
    Page<ConsultationSurvey> findAllWithDetails(Pageable pageable);

    @Query("""
        SELECT s FROM ConsultationSurvey s
        JOIN FETCH s.appointment
        JOIN FETCH s.patient
        JOIN FETCH s.doctor d
        JOIN FETCH d.user
        WHERE s.doctor.id = :doctorId
        ORDER BY s.submittedAt DESC
        """)
    Page<ConsultationSurvey> findByDoctorIdWithDetails(Long doctorId, Pageable pageable);
}
