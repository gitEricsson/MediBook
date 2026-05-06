package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
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
    private User docUser;
    private Doctor doctor;
    private Appointment appointment;
    private LocalDateTime futureTime;
    private UserPrincipal principal;     // patient principal
    private UserPrincipal docPrincipal;  // assigned doctor's principal
    private UserPrincipal adminPrincipal;
    private UserPrincipal unrelatedPrincipal;

    @BeforeEach
    void setUp() {
        futureTime = LocalDateTime.now().plusDays(3);

        patient = User.builder().id(1L).email("p@test.com")
                .firstName("Jane").lastName("Doe").role(Role.ROLE_PATIENT).build();

        Department dept = Department.builder().id(1L).name("Cardiology").build();
        docUser = User.builder().id(2L).email("doc@test.com")
                .firstName("Dr. Bob").lastName("Smith").role(Role.ROLE_DOCTOR).build();

        doctor = Doctor.builder().id(1L).user(docUser).department(dept)
                .licenseNumber("LIC-001").build();

        appointment = Appointment.builder()
                .id(10L).patient(patient).doctor(doctor)
                .scheduledAt(futureTime).status(AppointmentStatus.PENDING).build();

        principal         = UserPrincipal.fromUser(patient);
        docPrincipal      = UserPrincipal.fromUser(docUser);
        adminPrincipal    = UserPrincipal.fromUser(
                User.builder().id(99L).email("admin@test.com")
                        .firstName("Admin").lastName("User").role(Role.ROLE_ADMIN).build());
        unrelatedPrincipal = UserPrincipal.fromUser(
                User.builder().id(5L).email("other@test.com")
                        .firstName("Other").lastName("User").role(Role.ROLE_PATIENT).build());

        // lenient: cancel() calls getReferenceById — not needed in every test
        lenient().when(userRepository.getReferenceById(anyLong())).thenReturn(patient);
    }

    // ─── Book ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("book — success publishes Kafka event and returns response")
    void book_success() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);
        req.setDurationMins(30);

        when(appointmentRepository.existsConflict(eq(1L), eq(futureTime), any(LocalDateTime.class))).thenReturn(false);
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

        when(appointmentRepository.existsConflict(eq(1L), eq(futureTime), any(LocalDateTime.class))).thenReturn(true);

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

    // ─── Book (additional paths) ──────────────────────────────────────────────

    @Test
    @DisplayName("book — patient not found throws NOT_FOUND and never saves")
    void book_patientNotFound_throwsNotFound() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);

        when(appointmentRepository.existsConflict(eq(1L), eq(futureTime), any(LocalDateTime.class))).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.book(1L, req))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    @DisplayName("book — doctor not found throws NOT_FOUND and never saves")
    void book_doctorNotFound_throwsNotFound() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(99L);
        req.setScheduledAt(futureTime);

        when(appointmentRepository.existsConflict(eq(99L), eq(futureTime), any(LocalDateTime.class))).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.book(1L, req))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("book — DataIntegrityViolationException (race condition) maps to 409 SLOT_TAKEN")
    void book_dataIntegrityViolation_throwsConflict() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);

        when(appointmentRepository.existsConflict(eq(1L), eq(futureTime), any(LocalDateTime.class))).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenThrow(new DataIntegrityViolationException("slot key"));

        assertThatThrownBy(() -> appointmentService.book(1L, req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("SLOT_TAKEN");
                });
        verifyNoInteractions(eventProducer);
    }

    @Test
    @DisplayName("book — persisted appointment has PENDING status and correct associations")
    void book_persistedWithCorrectFields() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);
        req.setDurationMins(45);
        req.setReason("Annual check-up");

        when(appointmentRepository.existsConflict(eq(1L), eq(futureTime), any(LocalDateTime.class))).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.book(1L, req);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        Appointment saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(saved.getPatient()).isEqualTo(patient);
        assertThat(saved.getDoctor()).isEqualTo(doctor);
        assertThat(saved.getDurationMins()).isEqualTo(45);
        assertThat(saved.getReason()).isEqualTo("Annual check-up");
    }

    @Test
    @DisplayName("book — publishes BOOKED appointment event and APPOINTMENT_BOOKED audit event")
    void book_publishesCorrectEvents() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);

        when(appointmentRepository.existsConflict(eq(1L), eq(futureTime), any(LocalDateTime.class))).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.book(1L, req);

        verify(eventProducer).publishAppointmentEvent(
                argThat(e -> "BOOKED".equals(e.getEventType())));
        verify(eventProducer).publishAuditEvent(
                argThat(e -> "APPOINTMENT_BOOKED".equals(e.getAction())));
    }

    @Test
    @DisplayName("book — inactive doctor throws CONFLICT with DOCTOR_INACTIVE error code")
    void book_inactiveDoctor_throwsConflict() {
        Doctor inactiveDoctor = Doctor.builder().id(1L).user(docUser)
                .department(Department.builder().id(1L).name("Cardiology").build())
                .licenseNumber("LIC-001").isActive(false).build();

        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);

        when(appointmentRepository.existsConflict(eq(1L), eq(futureTime), any(LocalDateTime.class))).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(inactiveDoctor));

        assertThatThrownBy(() -> appointmentService.book(1L, req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("DOCTOR_INACTIVE");
                });
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("book — persisted appointment carries department and correct endTime")
    void book_setsEndTimeAndDepartmentOnSavedEntity() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(1L);
        req.setScheduledAt(futureTime);
        req.setDurationMins(45);

        when(appointmentRepository.existsConflict(eq(1L), eq(futureTime), any(LocalDateTime.class))).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.book(1L, req);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        Appointment saved = captor.getValue();
        assertThat(saved.getEndTime()).isEqualTo(futureTime.plusMinutes(45));
        assertThat(saved.getDepartment()).isEqualTo(doctor.getDepartment());
    }

    // ─── Confirm — additional guards ─────────────────────────────────────────

    @Test
    @DisplayName("confirm — non-PENDING appointment throws BAD_REQUEST INVALID_STATUS_TRANSITION")
    void confirm_nonPendingAppointment_throwsBadRequest() {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentService.confirm(10L, docPrincipal))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("INVALID_STATUS_TRANSITION");
                });
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    @DisplayName("confirm — success publishes both CONFIRMED and APPOINTMENT_CONFIRMED audit events")
    void confirm_publishesBothEvents() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.confirm(10L, docPrincipal);

        verify(eventProducer).publishAppointmentEvent(argThat(e -> "CONFIRMED".equals(e.getEventType())));
        verify(eventProducer).publishAuditEvent(argThat(e -> "APPOINTMENT_CONFIRMED".equals(e.getAction())));
    }

    // ─── Cancel — additional guards ──────────────────────────────────────────

    @Test
    @DisplayName("cancel — sets cancelledAt timestamp and cancelledBy actor on entity")
    void cancel_setsAuditFields() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        appointmentService.cancel(10L, principal);
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        Appointment saved = captor.getValue();
        assertThat(saved.getCancelledAt()).isNotNull()
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
        assertThat(saved.getCancelledBy()).isNotNull();
        verify(userRepository).getReferenceById(eq(patient.getId()));
    }

    @Test
    @DisplayName("cancel — publishes both CANCELLED and APPOINTMENT_CANCELLED audit events")
    void cancel_publishesBothEvents() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.cancel(10L, principal);

        verify(eventProducer).publishAppointmentEvent(argThat(e -> "CANCELLED".equals(e.getEventType())));
        verify(eventProducer).publishAuditEvent(argThat(e -> "APPOINTMENT_CANCELLED".equals(e.getAction())));
    }

    // ─── Confirm ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("confirm — assigned doctor sets CONFIRMED status and publishes event")
    void confirm_assignedDoctor_setsConfirmedAndPublishesEvent() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        AppointmentResponse response = appointmentService.confirm(10L, docPrincipal);

        assertThat(response).isNotNull();
        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(eventProducer).publishAppointmentEvent(
                argThat(e -> "CONFIRMED".equals(e.getEventType())));
    }

    @Test
    @DisplayName("confirm — admin can confirm any appointment regardless of assignment")
    void confirm_admin_succeeds() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        AppointmentResponse response = appointmentService.confirm(10L, adminPrincipal);

        assertThat(response).isNotNull();
        verify(appointmentRepository).save(any());
    }

    @Test
    @DisplayName("confirm — appointment not found throws NOT_FOUND")
    void confirm_notFound_throwsNotFound() {
        when(appointmentRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.confirm(99L, docPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirm — unrelated user throws FORBIDDEN and never saves")
    void confirm_unrelatedUser_throwsForbidden() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentService.confirm(10L, unrelatedPrincipal))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    // ─── Cancel (additional paths) ───────────────────────────────────────────

    @Test
    @DisplayName("cancel — appointment not found throws NOT_FOUND")
    void cancel_notFound_throwsNotFound() {
        when(appointmentRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.cancel(99L, principal))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancel — unrelated user throws FORBIDDEN and never saves")
    void cancel_unrelatedUser_throwsForbidden() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentService.cancel(10L, unrelatedPrincipal))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancel — patient can cancel their own appointment")
    void cancel_byPatient_succeeds() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.cancel(10L, principal);  // principal is the patient (id=1)

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel — assigned doctor can cancel the appointment")
    void cancel_byAssignedDoctor_succeeds() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.cancel(10L, docPrincipal);  // docPrincipal id=2 matches doctor.user.id

        verify(appointmentRepository).save(any());
        verify(eventProducer).publishAppointmentEvent(
                argThat(e -> "CANCELLED".equals(e.getEventType())));
    }

    @Test
    @DisplayName("cancel — admin can cancel any appointment")
    void cancel_byAdmin_succeeds() {
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.cancel(10L, adminPrincipal);

        verify(appointmentRepository).save(any());
    }

    @Test
    @DisplayName("cancel — CONFIRMED appointment can be cancelled (not just PENDING)")
    void cancel_confirmedAppointment_succeeds() {
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.cancel(10L, principal);

        verify(appointmentRepository).save(any());
    }

    // ─── Pagination ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getByPatient — returns page mapped to AppointmentResponse DTOs")
    void getByPatient_returnsMappedPage() {
        var pageable = PageRequest.of(0, 20);
        Page<Appointment> page = new PageImpl<>(List.of(appointment), pageable, 1);
        when(appointmentRepository.findByPatientId(1L, pageable)).thenReturn(page);

        Page<AppointmentResponse> result = appointmentService.getByPatient(1L, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("getByDoctor — returns page mapped to AppointmentResponse DTOs")
    void getByDoctor_returnsMappedPage() {
        var pageable = PageRequest.of(0, 20);
        Page<Appointment> page = new PageImpl<>(List.of(appointment), pageable, 1);
        when(appointmentRepository.findByDoctorId(1L, pageable)).thenReturn(page);

        Page<AppointmentResponse> result = appointmentService.getByDoctor(1L, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getDoctorId()).isEqualTo(doctor.getId());
    }
}
