package com.medibook.domain.doctor.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.dto.DoctorRequest;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

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

        Doctor doctor = builder.build();
        doctor.setSearchVector(buildSearchVector(user.getFirstName(), user.getLastName(), request.getSpecialization()));

        return DoctorResponse.fromEntity(doctorRepository.save(doctor));
    }

    @CacheEvict(value = "doctors", key = "#id")
    @Transactional
    public DoctorResponse update(Long id, DoctorRequest request) {
        Doctor doctor = doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));

        Department dept = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", request.getDepartmentId()));

        doctor.setDepartment(dept);
        doctor.setSpecialization(request.getSpecialization());
        doctor.setBio(request.getBio());
        if (request.getSlotDurationMins() != null) {
            doctor.setSlotDurationMins(request.getSlotDurationMins());
        }
        doctor.setSearchVector(buildSearchVector(
                doctor.getUser().getFirstName(), doctor.getUser().getLastName(), request.getSpecialization()));

        return DoctorResponse.fromEntity(doctorRepository.save(doctor));
    }

    private String buildSearchVector(String firstName, String lastName, String specialization) {
        return String.join(" ",
                firstName  != null ? firstName  : "",
                lastName   != null ? lastName   : "",
                specialization != null ? specialization : "").trim();
    }
}
