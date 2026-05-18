package com.medibook.domain.prescription.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.prescription.dto.PrescriptionDtos;
import com.medibook.domain.prescription.entity.Prescription;
import com.medibook.domain.prescription.entity.PrescriptionStatus;
import com.medibook.domain.prescription.repository.PrescriptionRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PrescriptionService — Unit Tests")
class PrescriptionServiceTest {

    @Mock PrescriptionRepository repository;
    @Mock AppointmentRepository appointmentRepository;
    @InjectMocks PrescriptionService service;

    private Appointment appt;
    private UserPrincipal doctorPrincipal;
    private UserPrincipal patientPrincipal;
    private UserPrincipal otherPatientPrincipal;
    private UserPrincipal adminPrincipal;

    @BeforeEach
    void setUp() {
        User docUser = User.builder().id(99L).email("d@test.com").build();
        Doctor doctor = Doctor.builder().id(7L).user(docUser).build();
        User patient = User.builder().id(50L).email("p@test.com").build();
        appt = Appointment.builder().id(1000L).patient(patient).doctor(doctor).build();

        doctorPrincipal       = principal(99L, Role.ROLE_DOCTOR);
        patientPrincipal      = principal(50L, Role.ROLE_PATIENT);
        otherPatientPrincipal = principal(51L, Role.ROLE_PATIENT);
        adminPrincipal        = principal(1L,  Role.ROLE_ADMIN);
    }

    @Test
    @DisplayName("create — patient cannot create; doctor on appointment can")
    void create_authGate() {
        when(appointmentRepository.findByIdWithDetails(1000L)).thenReturn(Optional.of(appt));
        var req = new PrescriptionDtos.CreateRequest(1000L, "Amoxicillin", "500mg", "PO", "Every 8 hours", 7, "Finish course");

        assertThatThrownBy(() -> service.create(req, patientPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Only the doctor");
        when(repository.save(any(Prescription.class))).thenAnswer(inv -> {
            Prescription p = inv.getArgument(0); p.setId(1L); return p;
        });
        var resp = service.create(req, doctorPrincipal);
        assertThat(resp.id()).isEqualTo(1L);
        assertThat(resp.status()).isEqualTo(PrescriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("cancel — rejects when already cancelled")
    void cancel_rejectsDoubleCancel() {
        Prescription rx = Prescription.builder()
                .id(1L).appointment(appt).doctor(appt.getDoctor()).patient(appt.getPatient())
                .status(PrescriptionStatus.CANCELLED)
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(rx));

        assertThatThrownBy(() -> service.cancel(1L, new PrescriptionDtos.CancelRequest("dup"), doctorPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("already cancelled");
    }

    @Test
    @DisplayName("update — rejects when prescription not ACTIVE")
    void update_rejectsNonActive() {
        Prescription rx = Prescription.builder()
                .id(1L).appointment(appt).doctor(appt.getDoctor()).patient(appt.getPatient())
                .status(PrescriptionStatus.COMPLETED)
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(rx));

        var update = new PrescriptionDtos.UpdateRequest("250mg", null, null, null, null);
        assertThatThrownBy(() -> service.update(1L, update, doctorPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Only active prescriptions");
    }

    @Test
    @DisplayName("listForPatient — other patient is blocked, admin sees")
    void listForPatient_auth() {
        assertThatThrownBy(() -> service.listForPatient(50L, null,
                org.springframework.data.domain.Pageable.unpaged(), otherPatientPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Not authorized");
        when(repository.findByPatientId(50L, org.springframework.data.domain.Pageable.unpaged()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        var page = service.listForPatient(50L, null,
                org.springframework.data.domain.Pageable.unpaged(), adminPrincipal);
        assertThat(page).isEmpty();
    }

    private static UserPrincipal principal(Long id, Role role) {
        User u = User.builder()
                .id(id).email("u" + id + "@test.com").firstName("X").lastName("Y")
                .role(role).enabled(true).isActive(true).build();
        return UserPrincipal.fromUser(u);
    }
}
