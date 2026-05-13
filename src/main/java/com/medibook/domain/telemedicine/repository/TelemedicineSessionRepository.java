package com.medibook.domain.telemedicine.repository;

import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TelemedicineSessionRepository extends JpaRepository<TelemedicineSession, Long> {

    Optional<TelemedicineSession> findByAppointmentId(Long appointmentId);

    @Query("""
        SELECT s FROM TelemedicineSession s
        JOIN FETCH s.appointment a
        JOIN FETCH a.patient
        JOIN FETCH a.doctor d
        JOIN FETCH d.user
        WHERE s.id = :id
        """)
    Optional<TelemedicineSession> findByIdWithDetails(Long id);
}
