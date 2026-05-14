package com.medibook.domain.patient.repository;

import com.medibook.domain.patient.entity.PatientAccessGrant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientAccessGrantRepository extends JpaRepository<PatientAccessGrant, Long> {

    Optional<PatientAccessGrant> findByPatientIdAndDoctorId(Long patientId, Long doctorId);

    Page<PatientAccessGrant> findByPatientIdAndStatus(Long patientId, PatientAccessGrant.AccessGrantStatus status, Pageable pageable);

    List<PatientAccessGrant> findByPatientIdAndStatus(Long patientId, PatientAccessGrant.AccessGrantStatus status);

    Page<PatientAccessGrant> findByDoctorIdAndStatus(Long doctorId, PatientAccessGrant.AccessGrantStatus status, Pageable pageable);

    Page<PatientAccessGrant> findByDoctorId(Long doctorId, Pageable pageable);

    boolean existsByPatientIdAndDoctorIdAndStatus(Long patientId, Long doctorId, PatientAccessGrant.AccessGrantStatus status);
}
