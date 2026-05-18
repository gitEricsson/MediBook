package com.medibook.domain.prescription.repository;

import com.medibook.domain.prescription.entity.Prescription;
import com.medibook.domain.prescription.entity.PrescriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    List<Prescription> findByAppointmentIdOrderByIssuedAtDesc(Long appointmentId);

    @Query("""
        SELECT p FROM Prescription p
        JOIN FETCH p.doctor d JOIN FETCH d.user
        WHERE p.patient.id = :patientId
        ORDER BY p.issuedAt DESC
        """)
    Page<Prescription> findByPatientId(Long patientId, Pageable pageable);

    @Query("""
        SELECT p FROM Prescription p
        JOIN FETCH p.doctor d JOIN FETCH d.user
        WHERE p.patient.id = :patientId AND p.status = :status
        ORDER BY p.issuedAt DESC
        """)
    Page<Prescription> findByPatientIdAndStatus(Long patientId, PrescriptionStatus status, Pageable pageable);
}
