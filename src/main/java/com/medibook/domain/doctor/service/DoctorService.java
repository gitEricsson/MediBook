package com.medibook.domain.doctor.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.dto.AdminCreateDoctorRequest;
import com.medibook.domain.doctor.dto.DoctorRequest;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.UserPrincipal;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;

    @Cacheable(value = "doctors", key = "#id")
    @Bulkhead(name = "doctorService")
    @Transactional(readOnly = true)
    public DoctorResponse getById(Long id) {
        return doctorRepository.findByIdWithDetails(id)
                .map(DoctorResponse::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
    }

    @Transactional(readOnly = true)
    public Page<DoctorResponse> getAll(Pageable pageable) {
        return doctorRepository.findAll(pageable).map(DoctorResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<DoctorResponse> getByDepartment(Long departmentId, Pageable pageable) {
        return doctorRepository.findByDepartmentId(departmentId, pageable)
                .map(DoctorResponse::fromEntity);
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
        builder.telemedicineEnabled(request.isTelemedicineEnabled());

        Doctor doctor = builder.build();
        doctor.setSearchVector(buildSearchVector(user.getFirstName(), user.getLastName(), request.getSpecialization()));

        return DoctorResponse.fromEntity(doctorRepository.save(doctor));
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
        return DoctorResponse.fromEntity(doctorRepository.save(doctor));
    }

    @CacheEvict(value = "doctors", key = "#id")
    @Transactional
    public DoctorResponse deactivate(Long id) {
        Doctor doctor = doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
        doctor.setActive(false);
        return DoctorResponse.fromEntity(doctorRepository.save(doctor));
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
        doctor.setTelemedicineEnabled(request.isTelemedicineEnabled());
        doctor.setSearchVector(buildSearchVector(
                doctor.getUser().getFirstName(), doctor.getUser().getLastName(), request.getSpecialization()));

        return DoctorResponse.fromEntity(doctorRepository.save(doctor));
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

        // Create user account with a temporary random password
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

        return DoctorResponse.fromEntity(doctorRepository.save(doctor));
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
}
