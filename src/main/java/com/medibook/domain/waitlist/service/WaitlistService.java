package com.medibook.domain.waitlist.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.domain.waitlist.dto.WaitlistRequest;
import com.medibook.domain.waitlist.dto.WaitlistResponse;
import com.medibook.domain.waitlist.entity.WaitlistEntry;
import com.medibook.domain.waitlist.repository.WaitlistRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private final WaitlistRepository   waitlistRepository;
    private final UserRepository       userRepository;
    private final DoctorRepository     doctorRepository;
    private final DepartmentRepository departmentRepository;

    @Transactional
    public WaitlistResponse joinWaitlist(WaitlistRequest req, UserPrincipal principal) {
        if (req.getDoctorId() == null && req.getDepartmentId() == null && req.getSpecialization() == null) {
            throw new MediBookException("At least one of doctorId, departmentId, or specialization must be provided",
                    HttpStatus.BAD_REQUEST, "WAITLIST_CRITERIA_MISSING");
        }

        if (req.getDoctorId() != null && waitlistRepository.existsByPatientIdAndDoctorIdAndStatus(
                principal.getId(), req.getDoctorId(), "WAITING")) {
            throw new MediBookException("You are already on the waitlist for this doctor",
                    HttpStatus.CONFLICT, "ALREADY_ON_WAITLIST");
        }

        User patient = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", principal.getId()));

        Doctor doctor = req.getDoctorId() != null
                ? doctorRepository.findById(req.getDoctorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", req.getDoctorId()))
                : null;

        Department department = req.getDepartmentId() != null
                ? departmentRepository.findById(req.getDepartmentId())
                        .orElseThrow(() -> new ResourceNotFoundException("Department", "id", req.getDepartmentId()))
                : null;

        WaitlistEntry entry = WaitlistEntry.builder()
                .patient(patient)
                .doctor(doctor)
                .department(department)
                .specialization(req.getSpecialization())
                .preferredDate(req.getPreferredDate())
                .status("WAITING")
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();

        WaitlistEntry saved = waitlistRepository.save(entry);
        log.info("Patient [{}] joined waitlist entry [{}]", principal.getId(), saved.getId());
        return WaitlistResponse.fromEntity(saved);
    }

    @Transactional
    public void leaveWaitlist(Long entryId, UserPrincipal principal) {
        WaitlistEntry entry = waitlistRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("WaitlistEntry", "id", entryId));

        if (!entry.getPatient().getId().equals(principal.getId()) && !principal.hasRole("ROLE_ADMIN")) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        entry.setStatus("CANCELLED");
        waitlistRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public Page<WaitlistResponse> getMyWaitlist(UserPrincipal principal, Pageable pageable) {
        return waitlistRepository.findByPatientId(principal.getId(), pageable)
                .map(WaitlistResponse::fromEntity);
    }
}
