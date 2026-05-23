package com.medibook.domain.patient.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.notification.service.NotificationService;
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

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessGrantService {

    private final PatientAccessGrantRepository accessGrantRepository;
    private final UserRepository               userRepository;
    private final DoctorRepository             doctorRepository;
    private final NotificationService          notificationService;

    /**
     * Upsert a time-bounded access grant created from a FOLLOW_UP booking consent.
     *
     * <p>If a grant already exists for the (patient, doctor) pair we widen its
     * {@code accessUpToDate} to the new cutoff (so the latest booking date wins)
     * and re-approve it. Otherwise create a fresh APPROVED grant with the supplied
     * cutoff. Idempotent and safe to call from {@code AppointmentService.book()}
     * on every follow-up booking.
     */
    @Transactional
    public PatientAccessGrant upsertFollowUpGrant(Long patientId, Long doctorId, LocalDate accessUpToDate) {
        User patient = userRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", patientId));
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));

        PatientAccessGrant grant = accessGrantRepository
                .findByPatientIdAndDoctorId(patientId, doctorId)
                .orElseGet(() -> PatientAccessGrant.builder()
                        .patient(patient)
                        .doctor(doctor)
                        .build());

        grant.setStatus(PatientAccessGrant.AccessGrantStatus.APPROVED);
        grant.setRevokedAt(null);
        // Widen the cutoff if a later booking grants access through a more recent date.
        if (grant.getAccessUpToDate() == null || accessUpToDate.isAfter(grant.getAccessUpToDate())) {
            grant.setAccessUpToDate(accessUpToDate);
        }
        if (grant.getReason() == null) {
            grant.setReason("Auto-granted via follow-up consultation consent");
        }

        PatientAccessGrant saved = accessGrantRepository.save(grant);
        log.info("Follow-up auto-grant upserted: patient=[{}] doctor=[{}] accessUpTo=[{}]",
                patientId, doctorId, saved.getAccessUpToDate());
        return saved;
    }

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

    // ── Doctor-initiated request flow ────────────────────────────────────────

    @Transactional
    public AccessGrantResponse requestAccess(Long doctorId, Long patientId, String reason) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));
        User patient = userRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", patientId));

        accessGrantRepository.findByPatientIdAndDoctorId(patientId, doctorId).ifPresent(existing -> {
            switch (existing.getStatus()) {
                case PENDING -> throw new MediBookException("Access request already pending", HttpStatus.CONFLICT, "REQUEST_PENDING");
                case APPROVED -> throw new MediBookException("Access already granted", HttpStatus.CONFLICT, "ALREADY_GRANTED");
                case REVOKED -> {
                    accessGrantRepository.delete(existing);
                    accessGrantRepository.flush();
                }
            }
        });

        PatientAccessGrant grant = PatientAccessGrant.builder()
                .patient(patient)
                .doctor(doctor)
                .status(PatientAccessGrant.AccessGrantStatus.PENDING)
                .reason(reason)
                .build();

        PatientAccessGrant saved = accessGrantRepository.save(grant);
        log.info("Doctor [{}] requested access to patient [{}] records", doctorId, patientId);

        String doctorName = doctor.getUser().getFirstName() + " " + doctor.getUser().getLastName();
        notificationService.sendAccessRequestNotification(patientId, doctorName, saved.getId());

        return AccessGrantResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Page<AccessGrantResponse> getIncomingRequests(Long patientId, Pageable pageable) {
        return accessGrantRepository.findByPatientIdAndStatus(
                patientId, PatientAccessGrant.AccessGrantStatus.PENDING, pageable)
                .map(AccessGrantResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<AccessGrantResponse> getOutgoingRequests(Long doctorId, Pageable pageable) {
        return accessGrantRepository.findByDoctorIdAndStatus(
                doctorId, PatientAccessGrant.AccessGrantStatus.PENDING, pageable)
                .map(AccessGrantResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<AccessGrantResponse> getAllDoctorGrants(Long doctorId, Pageable pageable) {
        return accessGrantRepository.findByDoctorId(doctorId, pageable)
                .map(AccessGrantResponse::fromEntity);
    }

    @Transactional
    public AccessGrantResponse approveRequest(Long patientId, Long grantId) {
        PatientAccessGrant grant = accessGrantRepository.findById(grantId)
                .orElseThrow(() -> new ResourceNotFoundException("Access Grant", "id", grantId));
        if (!grant.getPatient().getId().equals(patientId)) {
            throw new MediBookException("Not authorized to approve this request", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (grant.getStatus() != PatientAccessGrant.AccessGrantStatus.PENDING) {
            throw new MediBookException("Only pending requests can be approved", HttpStatus.BAD_REQUEST, "INVALID_STATE");
        }
        grant.setStatus(PatientAccessGrant.AccessGrantStatus.APPROVED);
        PatientAccessGrant saved = accessGrantRepository.save(grant);
        log.info("Patient [{}] approved access for doctor [{}]", patientId, grant.getDoctor().getId());

        String doctorUserId = String.valueOf(grant.getDoctor().getUser().getId());
        notificationService.sendAccessGrantedNotification(grant.getDoctor().getUser().getId(),
                grant.getPatient().getFirstName() + " " + grant.getPatient().getLastName());

        return AccessGrantResponse.fromEntity(saved);
    }

    @Transactional
    public AccessGrantResponse denyRequest(Long patientId, Long grantId) {
        PatientAccessGrant grant = accessGrantRepository.findById(grantId)
                .orElseThrow(() -> new ResourceNotFoundException("Access Grant", "id", grantId));
        if (!grant.getPatient().getId().equals(patientId)) {
            throw new MediBookException("Not authorized to deny this request", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (grant.getStatus() != PatientAccessGrant.AccessGrantStatus.PENDING) {
            throw new MediBookException("Only pending requests can be denied", HttpStatus.BAD_REQUEST, "INVALID_STATE");
        }
        grant.setStatus(PatientAccessGrant.AccessGrantStatus.REVOKED);
        grant.setRevokedAt(java.time.LocalDateTime.now());
        PatientAccessGrant saved = accessGrantRepository.save(grant);
        log.info("Patient [{}] denied access for doctor [{}]", patientId, grant.getDoctor().getId());
        return AccessGrantResponse.fromEntity(saved);
    }
}
