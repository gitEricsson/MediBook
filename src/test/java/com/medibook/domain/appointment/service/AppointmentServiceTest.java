package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.messaging.producer.AppointmentEventProducer;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentService — Unit Tests")
class AppointmentServiceTest {

    @Mock AppointmentRepository appointmentRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock UserRepository userRepository;
    @Mock AppointmentEventProducer eventProducer;

    @InjectMocks AppointmentService appointmentService;

    private User patient;
    private Doctor doctor;
    private Appointment appointment;
    private LocalDateTime futureTime;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        futureTime = LocalDateTime.now().plusDays(3);

        patient = User.builder().id(1L).email("p@test.com")
                .firstName("Jane").lastName("Doe").role(Role.ROLE_PATIENT).build();

        Department dept = Department.builder().id(1L).name("Cardiology").build();
        User docUser = User.builder().id(2L).email("doc@test.com")
                .firstName("Dr. Bob").lastName("Smith").role(Role.ROLE_DOCTOR).build();

        doctor = Doctor.builder().id(1L).user(docUser).department(dept)
                .licenseNumber("LIC-001").build();

        appointment = Appointment.builder()
                .id(10L).patient(patient).doctor(doctor)
                .scheduledAt(futureTime).status(AppointmentStatus.PENDING).build();
        principal = UserPrincipal.fromUser(patient);
    }

    // ─── Book ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("book — success publishes Kafka event and returns response")
    void book_success() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);
        req.setDurationMins(30);

        when(appointmentRepository.existsConflict(1L, futureTime)).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        AppointmentResponse response = appointmentService.book(1L, req);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(10L);
        verify(eventProducer).publishAppointmentEvent(any());
        verify(eventProducer).publishAuditEvent(any());
    }

    @Test
    @DisplayName("book — slot conflict throws CONFLICT exception")
    void book_conflict_throwsException() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);

        when(appointmentRepository.existsConflict(1L, futureTime)).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.book(1L, req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    // ─── Cancel ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel — PENDING appointment succeeds and publishes CANCELLED event")
    void cancel_pending_succeeds() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        AppointmentResponse response = appointmentService.cancel(10L, principal);

        assertThat(response).isNotNull();
        verify(eventProducer).publishAppointmentEvent(argThat(e -> "CANCELLED".equals(e.getEventType())));
    }

    @Test
    @DisplayName("cancel — COMPLETED appointment throws BAD_REQUEST")
    void cancel_completed_throwsException() {
        appointment.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentService.cancel(10L, principal))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ─── GetById ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getById — existing appointment returns response")
    void getById_existing() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        AppointmentResponse response = appointmentService.getById(10L);
        assertThat(response.getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("getById — non-existing throws ResourceNotFoundException")
    void getById_notFound() {
        when(appointmentRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> appointmentService.getById(99L))
                .hasMessageContaining("Appointment");
    }
}
