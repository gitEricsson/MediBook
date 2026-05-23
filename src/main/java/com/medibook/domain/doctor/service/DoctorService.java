package com.medibook.domain.doctor.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.config.HospitalProperties;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import com.medibook.domain.doctor.dto.AdminCreateDoctorRequest;
import com.medibook.domain.doctor.dto.DoctorRequest;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.messaging.event.AuditEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import com.medibook.security.UserPrincipal;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository         doctorRepository;
    private final UserRepository           userRepository;
    private final DepartmentRepository     departmentRepository;
    private final PasswordEncoder          passwordEncoder;
    private final AppointmentEventProducer eventProducer;
    private final HospitalProperties       hospitalProperties;
    private final com.medibook.domain.user.service.PasswordResetService passwordResetService;

    @Cacheable(value = "doctors", key = "#id")
    @Bulkhead(name = "doctorService")
    @Transactional(readOnly = true)
    public DoctorResponse getById(Long id) {
        return doctorRepository.findByIdWithDetails(id)
                .map(d -> DoctorResponse.fromEntity(d, hospitalProperties))
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
    }

    @Transactional(readOnly = true)
    public Page<DoctorResponse> getAll(Pageable pageable) {
        return doctorRepository.findAll(pageable).map(d -> DoctorResponse.fromEntity(d, hospitalProperties));
    }

    @Transactional(readOnly = true)
    public Page<DoctorResponse> getByDepartment(Long departmentId, Pageable pageable) {
        return doctorRepository.findByDepartmentId(departmentId, pageable)
                .map(d -> DoctorResponse.fromEntity(d, hospitalProperties));
    }

    @CacheEvict(value = "doctors", allEntries = true)
    @Transactional
    public DoctorResponse register(DoctorRequest request) {
        if (doctorRepository.existsByLicenseNumber(request.getLicenseNumber())) {
            throw new MediBookException("License number already registered",
                    HttpStatus.CONFLICT, "LICENSE_TAKEN");
        }

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.getUserId()));

        Department dept = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", request.getDepartmentId()));

        Doctor.DoctorBuilder builder = Doctor.builder()
                .user(user)
                .department(dept)
                .specialization(request.getSpecialization())
                .licenseNumber(request.getLicenseNumber())
                .bio(request.getBio());

        if (request.getSlotDurationMins() != null) {
            builder.slotDurationMins(request.getSlotDurationMins());
        }
        if (request.getYearsOfExperience() != null) {
            builder.yearsOfExperience(request.getYearsOfExperience());
        }
        if (request.getConsultationFee() != null) {
            builder.consultationFee(request.getConsultationFee());
        }
        if (request.getGender() != null) {
            builder.gender(request.getGender());
        }
        if (request.getLanguages() != null) {
            builder.languages(request.getLanguages());
        }

        Doctor doctor = builder.build();
        doctor.setSearchVector(buildSearchVector(user.getFirstName(), user.getLastName(), request.getSpecialization()));
        applyAdditionalDepts(doctor, request.getAdditionalDepartmentIds());
        applySpecializations(doctor, request.getSpecialization(), request.getSpecializations());

        return DoctorResponse.fromEntity(doctorRepository.save(doctor), hospitalProperties);
    }

    @CacheEvict(value = "doctors", key = "#id")
    @Transactional
    public DoctorResponse update(Long id, DoctorRequest request) {
        Doctor doctor = doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
        return updateLoaded(doctor, request);
    }

    @CacheEvict(value = "doctors", key = "#id")
    @Transactional
    public DoctorResponse update(Long id, DoctorRequest request, UserPrincipal principal) {
        Doctor doctor = doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
        ensureCanManageDoctor(doctor, principal);
        return updateLoaded(doctor, request);
    }

    @CacheEvict(value = "doctors", key = "#id")
    @Transactional
    public DoctorResponse activate(Long id) {
        Doctor doctor = doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
        doctor.setActive(true);
        DoctorResponse result = DoctorResponse.fromEntity(doctorRepository.save(doctor), hospitalProperties);

        // Emit DOCTOR_ACTIVATED audit event
        emitDoctorAudit("DOCTOR_ACTIVATED", id, doctor.getUser().getId(), "Doctor profile activated");

        return result;
    }

    @CacheEvict(value = "doctors", key = "#id")
    @Transactional
    public DoctorResponse deactivate(Long id) {
        Doctor doctor = doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
        doctor.setActive(false);
        DoctorResponse result = DoctorResponse.fromEntity(doctorRepository.save(doctor), hospitalProperties);

        // Emit DOCTOR_DEACTIVATED audit event
        emitDoctorAudit("DOCTOR_DEACTIVATED", id, doctor.getUser().getId(), "Doctor profile deactivated");

        return result;
    }

    private void emitDoctorAudit(String action, Long doctorId, Long userId, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long actorId = null;
        String actorEmail = "SYSTEM";

        if (auth != null && auth.getPrincipal() instanceof UserPrincipal) {
            UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
            actorId = principal.getId();
            actorEmail = principal.getEmail();
        }

        AuditEvent auditEvent = AuditEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .action(action)
                .actorId(actorId)
                .actorEmail(actorEmail)
                .resourceType("Doctor")
                .resourceId(String.valueOf(doctorId))
                .detail(detail + " (User ID: " + userId + ")")
                .occurredAt(LocalDateTime.now())
                .build();
        eventProducer.publishAuditEvent(auditEvent);
    }

    private DoctorResponse updateLoaded(Doctor doctor, DoctorRequest request) {
        Department dept = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", request.getDepartmentId()));

        doctor.setDepartment(dept);
        doctor.setSpecialization(request.getSpecialization());
        doctor.setBio(request.getBio());
        if (request.getSlotDurationMins() != null) {
            doctor.setSlotDurationMins(request.getSlotDurationMins());
        }
        if (request.getYearsOfExperience() != null) {
            doctor.setYearsOfExperience(request.getYearsOfExperience());
        }
        if (request.getConsultationFee() != null) {
            doctor.setConsultationFee(request.getConsultationFee());
        }
        if (request.getGender() != null) {
            doctor.setGender(request.getGender());
        }
        if (request.getLanguages() != null) {
            doctor.setLanguages(request.getLanguages());
        }
        doctor.setSearchVector(buildSearchVector(
                doctor.getUser().getFirstName(), doctor.getUser().getLastName(), request.getSpecialization()));
        applyAdditionalDepts(doctor, request.getAdditionalDepartmentIds());
        applySpecializations(doctor, request.getSpecialization(), request.getSpecializations());

        return DoctorResponse.fromEntity(doctorRepository.save(doctor), hospitalProperties);
    }

    /**
     * Admin-only: provision a new User + Doctor in a single transaction.
     * Generates a random temporary password; the doctor must reset it on first login.
     */
    @CacheEvict(value = "doctors", allEntries = true)
    @Transactional
    public DoctorResponse adminCreateDoctor(AdminCreateDoctorRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new MediBookException("Email already registered",
                    HttpStatus.CONFLICT, "EMAIL_TAKEN");
        }

        Department dept = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", request.getDepartmentId()));

        // Provision the user with an unguessable temporary password that the
        // doctor will never use directly. They receive an invite email with a
        // single-use setup-password token (7-day TTL) and choose their own
        // password before they can sign in. We still keep the account `enabled`
        // so the BE's pre-auth checks pass when they hit /auth/reset-password —
        // the security gate is the inability to log in without knowing the
        // random password we never reveal.
        String tempPassword = UUID.randomUUID().toString();
        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .password(passwordEncoder.encode(tempPassword))
                .role(Role.ROLE_DOCTOR)
                .isActive(true)
                .enabled(true)
                .build();
        User savedUser = userRepository.save(user);

        Doctor doctor = Doctor.builder()
                .user(savedUser)
                .department(dept)
                .specialization(request.getSpecialization())
                .licenseNumber(request.getLicenseNumber() != null ? request.getLicenseNumber() : "PENDING-" + savedUser.getId())
                .bio(request.getBio())
                .build();
        doctor.setSearchVector(buildSearchVector(
                request.getFirstName(), request.getLastName(), request.getSpecialization()));
        applyAdditionalDepts(doctor, request.getAdditionalDepartmentIds());
        applySpecializations(doctor, request.getSpecialization(), request.getSpecializations());

        Doctor saved = doctorRepository.save(doctor);

        // Dispatch the welcome / setup-password email. Async + try-wrapped so a
        // mail outage cannot fail the provisioning transaction — the admin can
        // resend later via the "forgot password" flow if needed.
        try {
            String inviteToken = passwordResetService.createInviteToken(savedUser.getId());
            passwordResetService.sendInviteEmail(
                    savedUser.getEmail(),
                    savedUser.getFirstName() + " " + savedUser.getLastName(),
                    "Doctor",
                    inviteToken);
        } catch (Exception ex) {
            // Don't fail the transaction on mail issues. The doctor row is saved
            // and the admin can resend the invite from the doctor management UI.
        }

        return DoctorResponse.fromEntity(saved, hospitalProperties);
    }

    private void ensureCanManageDoctor(Doctor doctor, UserPrincipal principal) {
        if (principal.hasRole("ROLE_ADMIN")) {
            return;
        }
        if (!doctor.getUser().getId().equals(principal.getId())) {
            throw new MediBookException("Not authorized to manage this doctor profile",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }

    private String buildSearchVector(String firstName, String lastName, String specialization) {
        return String.join(" ",
                firstName  != null ? firstName  : "",
                lastName   != null ? lastName   : "",
                specialization != null ? specialization : "").trim();
    }

    private void applyAdditionalDepts(Doctor doctor, java.util.List<Long> additionalDeptIds) {
        if (additionalDeptIds == null || additionalDeptIds.isEmpty()) {
            doctor.getAdditionalDepartments().clear();
            return;
        }
        Set<Department> depts = additionalDeptIds.stream()
                .filter(id -> !id.equals(doctor.getDepartment().getId()))
                .map(id -> departmentRepository.findById(id)
                        .orElseThrow(() -> new com.medibook.common.exception.ResourceNotFoundException("Department", "id", id)))
                .collect(Collectors.toSet());
        doctor.getAdditionalDepartments().clear();
        doctor.getAdditionalDepartments().addAll(depts);
    }

    private void applySpecializations(Doctor doctor, String primary, java.util.List<String> extras) {
        Set<String> all = new HashSet<>();
        if (primary != null && !primary.isBlank()) {
            all.add(primary.trim());
        }
        if (extras != null) {
            extras.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .forEach(all::add);
        }
        doctor.getSpecializations().clear();
        doctor.getSpecializations().addAll(all);
    }
}
