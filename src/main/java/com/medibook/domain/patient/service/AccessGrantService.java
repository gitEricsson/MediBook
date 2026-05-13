package com.medibook.domain.patient.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.patient.dto.AccessGrantRequest;
import com.medibook.domain.patient.dto.AccessGrantResponse;
import com.medibook.domain.patient.entity.PatientAccessGrant;
import com.medibook.domain.patient.repository.PatientAccessGrantRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessGrantService {

    private final PatientAccessGrantRepository accessGrantRepository;
    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;

    @Transactional
    public AccessGrantResponse grantAccess(Long patientId, AccessGrantRequest request) {
        User patient = userRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", patientId));

        Doctor doctor = doctorRepository.findById(request.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", request.getDoctorId()));

        accessGrantRepository.findByPatientIdAndDoctorId(patientId, request.getDoctorId())
                .ifPresent(existing -> {
                    throw new MediBookException(
                            "Access grant already exists for this doctor",
                            HttpStatus.CONFLICT,
                            "ACCESS_GRANT_EXISTS"
                    );
                });

        PatientAccessGrant grant = PatientAccessGrant.builder()
                .patient(patient)
                .doctor(doctor)
                .status(PatientAccessGrant.AccessGrantStatus.APPROVED)
                .reason(request.getReason())
                .build();

        PatientAccessGrant saved = accessGrantRepository.save(grant);
        log.info("Patient [{}] granted access to doctor [{}]", patientId, request.getDoctorId());

        return AccessGrantResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Page<AccessGrantResponse> getPatientGrants(Long patientId, Pageable pageable) {
        return accessGrantRepository.findByPatientIdAndStatus(
                patientId,
                PatientAccessGrant.AccessGrantStatus.APPROVED,
                pageable
        ).map(AccessGrantResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public AccessGrantResponse getGrant(Long patientId, Long grantId) {
        PatientAccessGrant grant = accessGrantRepository.findById(grantId)
                .orElseThrow(() -> new ResourceNotFoundException("Access Grant", "id", grantId));

        if (!grant.getPatient().getId().equals(patientId)) {
            throw new MediBookException(
                    "Not authorized to access this grant",
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED"
            );
        }

        return AccessGrantResponse.fromEntity(grant);
    }

    @Transactional
    public void revokeAccess(Long patientId, Long grantId) {
        PatientAccessGrant grant = accessGrantRepository.findById(grantId)
                .orElseThrow(() -> new ResourceNotFoundException("Access Grant", "id", grantId));

        if (!grant.getPatient().getId().equals(patientId)) {
            throw new MediBookException(
                    "Not authorized to revoke this access",
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED"
            );
        }

        grant.setStatus(PatientAccessGrant.AccessGrantStatus.REVOKED);
        grant.setRevokedAt(java.time.LocalDateTime.now());
        accessGrantRepository.save(grant);

        log.info("Patient [{}] revoked access from doctor [{}]", patientId, grant.getDoctor().getId());
    }

    @Transactional(readOnly = true)
    public boolean hasAccess(Long patientId, Long doctorId) {
        return accessGrantRepository.existsByPatientIdAndDoctorIdAndStatus(
                patientId,
                doctorId,
                PatientAccessGrant.AccessGrantStatus.APPROVED
        );
    }
}
