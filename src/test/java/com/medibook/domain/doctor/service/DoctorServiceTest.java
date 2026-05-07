package com.medibook.domain.doctor.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.dto.DoctorRequest;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoctorService — Unit Tests")
class DoctorServiceTest {

    @Mock DoctorRepository     doctorRepository;
    @Mock UserRepository       userRepository;
    @Mock DepartmentRepository departmentRepository;

    @InjectMocks DoctorService doctorService;

    private User       docUser;
    private Department dept;
    private Doctor     doctor;

    @BeforeEach
    void setUp() {
        docUser = User.builder().id(1L).email("doc@test.com")
                .firstName("Bob").lastName("Doctor").role(Role.ROLE_DOCTOR).build();
        dept = Department.builder().id(10L).name("Cardiology").code("CARD").build();
        doctor = Doctor.builder().id(100L).user(docUser).department(dept)
                .licenseNumber("LIC-001").specialization("Cardiology").bio("Experienced").build();
    }

    // ─── getById ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getById — existing doctor returns response with user and department fields")
    void getById_existing_returnsMappedResponse() {
        when(doctorRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(doctor));

        DoctorResponse response = doctorService.getById(100L);

        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getFullName()).isEqualTo("Bob Doctor");
        assertThat(response.getDepartmentName()).isEqualTo("Cardiology");
        assertThat(response.getLicenseNumber()).isEqualTo("LIC-001");
    }

    @Test
    @DisplayName("getById — unknown ID throws NOT_FOUND")
    void getById_notFound_throwsNotFound() {
        when(doctorRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.getById(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── getAll ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getAll — returns paginated list of doctors")
    void getAll_returnsMappedPage() {
        var pageable = PageRequest.of(0, 20);
        when(doctorRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(doctor), pageable, 1));

        Page<DoctorResponse> result = doctorService.getAll(pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getLicenseNumber()).isEqualTo("LIC-001");
    }

    // ─── getByDepartment ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getByDepartment — returns doctors filtered by department")
    void getByDepartment_returnsMappedPage() {
        var pageable = PageRequest.of(0, 20);
        when(doctorRepository.findByDepartmentId(10L, pageable))
                .thenReturn(new PageImpl<>(List.of(doctor), pageable, 1));

        Page<DoctorResponse> result = doctorService.getByDepartment(10L, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getDepartmentId()).isEqualTo(10L);
    }

    // ─── register ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("register — success creates doctor with all fields from request")
    void register_success_createsDoctorCorrectly() {
        DoctorRequest req = buildRequest(1L, 10L, "LIC-NEW", "Neurology", "Specialist");
        when(doctorRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(docUser));
        when(departmentRepository.findById(10L)).thenReturn(Optional.of(dept));
        when(doctorRepository.save(any())).thenReturn(doctor);

        DoctorResponse response = doctorService.register(req);

        assertThat(response).isNotNull();
        ArgumentCaptor<Doctor> captor = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository).save(captor.capture());
        Doctor saved = captor.getValue();
        assertThat(saved.getLicenseNumber()).isEqualTo("LIC-NEW");
        assertThat(saved.getSpecialization()).isEqualTo("Neurology");
        assertThat(saved.getBio()).isEqualTo("Specialist");
        assertThat(saved.getUser()).isEqualTo(docUser);
        assertThat(saved.getDepartment()).isEqualTo(dept);
    }

    @Test
    @DisplayName("register — duplicate license throws 409 LICENSE_TAKEN")
    void register_duplicateLicense_throwsConflict() {
        DoctorRequest req = buildRequest(1L, 10L, "LIC-001", null, null);
        when(doctorRepository.existsByLicenseNumber("LIC-001")).thenReturn(true);

        assertThatThrownBy(() -> doctorService.register(req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("LICENSE_TAKEN");
                });
        verify(doctorRepository, never()).save(any());
    }

    @Test
    @DisplayName("register — user not found throws NOT_FOUND")
    void register_userNotFound_throwsNotFound() {
        DoctorRequest req = buildRequest(99L, 10L, "LIC-NEW", null, null);
        when(doctorRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.register(req))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(doctorRepository, never()).save(any());
    }

    @Test
    @DisplayName("register — department not found throws NOT_FOUND")
    void register_departmentNotFound_throwsNotFound() {
        DoctorRequest req = buildRequest(1L, 99L, "LIC-NEW", null, null);
        when(doctorRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(docUser));
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.register(req))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(doctorRepository, never()).save(any());
    }

    // ─── update ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update — success updates department, specialization, and bio")
    void update_success_updatesCorrectFields() {
        Department newDept = Department.builder().id(20L).name("Neurology").code("NEUR").build();
        DoctorRequest req = buildRequest(1L, 20L, "LIC-001", "Neuro", "Updated bio");
        when(doctorRepository.findById(100L)).thenReturn(Optional.of(doctor));
        when(departmentRepository.findById(20L)).thenReturn(Optional.of(newDept));
        when(doctorRepository.save(any())).thenReturn(doctor);

        doctorService.update(100L, req);

        ArgumentCaptor<Doctor> captor = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository).save(captor.capture());
        assertThat(captor.getValue().getDepartment()).isEqualTo(newDept);
        assertThat(captor.getValue().getSpecialization()).isEqualTo("Neuro");
        assertThat(captor.getValue().getBio()).isEqualTo("Updated bio");
    }

    @Test
    @DisplayName("update — doctor not found throws NOT_FOUND")
    void update_notFound_throwsNotFound() {
        when(doctorRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.update(999L, buildRequest(1L, 10L, "LIC-001", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(doctorRepository, never()).save(any());
    }

    @Test
    @DisplayName("update — department not found throws NOT_FOUND")
    void update_departmentNotFound_throwsNotFound() {
        when(doctorRepository.findById(100L)).thenReturn(Optional.of(doctor));
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.update(100L, buildRequest(1L, 99L, "LIC-001", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(doctorRepository, never()).save(any());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private DoctorRequest buildRequest(Long userId, Long deptId, String license, String spec, String bio) {
        DoctorRequest req = new DoctorRequest();
        req.setUserId(userId);
        req.setDepartmentId(deptId);
        req.setLicenseNumber(license);
        req.setSpecialization(spec);
        req.setBio(bio);
        return req;
    }
}
