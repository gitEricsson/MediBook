package com.medibook.domain.waitlist.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.domain.waitlist.dto.WaitlistRequest;
import com.medibook.domain.waitlist.dto.WaitlistResponse;
import com.medibook.domain.waitlist.entity.WaitlistEntry;
import com.medibook.domain.waitlist.repository.WaitlistRepository;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    @Mock WaitlistRepository   waitlistRepository;
    @Mock UserRepository       userRepository;
    @Mock DoctorRepository     doctorRepository;
    @Mock DepartmentRepository departmentRepository;

    @InjectMocks WaitlistService waitlistService;

    private User patient;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        patient = User.builder().id(1L).email("p@test.com").firstName("Jane").lastName("Doe")
                .role(Role.ROLE_PATIENT).build();
        principal = UserPrincipal.fromUser(patient);
    }

    @Test
    void joinWaitlist_withDoctorId_success() {
        WaitlistRequest req = new WaitlistRequest();
        req.setDoctorId(10L);

        User docUser = User.builder().id(2L).firstName("Dr").lastName("X").build();
        com.medibook.domain.department.entity.Department dept =
                com.medibook.domain.department.entity.Department.builder().id(1L).name("Gen").build();
        Doctor doctor = Doctor.builder().id(10L).user(docUser).department(dept).licenseNumber("L1").build();

        when(waitlistRepository.existsByPatientIdAndDoctorIdAndStatus(1L, 10L, "WAITING")).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor));

        WaitlistEntry saved = WaitlistEntry.builder()
                .id(1L).patient(patient).doctor(doctor).status("WAITING").build();
        when(waitlistRepository.save(any())).thenReturn(saved);

        WaitlistResponse response = waitlistService.joinWaitlist(req, principal);

        assertThat(response.getStatus()).isEqualTo("WAITING");
        assertThat(response.getDoctorId()).isEqualTo(10L);
    }

    @Test
    void joinWaitlist_noCriteria_throwsException() {
        WaitlistRequest req = new WaitlistRequest();

        assertThatThrownBy(() -> waitlistService.joinWaitlist(req, principal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("At least one of");
    }

    @Test
    void joinWaitlist_alreadyOnWaitlist_throwsConflict() {
        WaitlistRequest req = new WaitlistRequest();
        req.setDoctorId(10L);

        when(waitlistRepository.existsByPatientIdAndDoctorIdAndStatus(1L, 10L, "WAITING")).thenReturn(true);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(req, principal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("already on the waitlist");
    }
}
