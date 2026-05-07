package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.dto.TransitionRequest;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.messaging.producer.AppointmentEventProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentTransitionService — Unit Tests")
class AppointmentTransitionServiceTest {

    @Mock AppointmentRepository appointmentRepository;
    @Mock AppointmentEventProducer eventProducer;

    @InjectMocks AppointmentTransitionService transitionService;

    private Appointment appointment;
    private static final Long DOCTOR_USER_ID = 2L;  // user ID of the assigned doctor
    private static final Long DOCTOR_ENTITY_ID = 10L;

    @BeforeEach
    void setUp() {
        User docUser = User.builder().id(DOCTOR_USER_ID).email("doc@test.com")
                .firstName("Bob").lastName("Doctor").role(Role.ROLE_DOCTOR).build();
        User patient = User.builder().id(1L).email("pat@test.com")
                .firstName("Alice").lastName("Patient").role(Role.ROLE_PATIENT).build();
        Department dept = Department.builder().id(1L).name("Cardiology").build();
        Doctor doctor = Doctor.builder().id(DOCTOR_ENTITY_ID).user(docUser).department(dept)
                .licenseNumber("LIC-001").build();

        appointment = Appointment.builder()
                .id(100L).patient(patient).doctor(doctor)
                .scheduledAt(LocalDateTime.now().plusDays(2))
                .status(AppointmentStatus.CONFIRMED)
                .confirmationCode("MB-TEST01")
                .build();
    }


    @Test
    @DisplayName("transition — appointment not found throws NOT_FOUND")
    void transition_notFound_throwsNotFound() {
        when(appointmentRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
        TransitionRequest req = transitionReq(AppointmentStatus.COMPLETED, null);

        assertThatThrownBy(() -> transitionService.transition(999L, req, DOCTOR_USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("transition — wrong doctor ID throws FORBIDDEN ACCESS_DENIED")
    void transition_wrongDoctor_throwsForbidden() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        TransitionRequest req = transitionReq(AppointmentStatus.COMPLETED, null);

        assertThatThrownBy(() -> transitionService.transition(100L, req, 99L))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("ACCESS_DENIED");
                });
        verify(appointmentRepository, never()).save(any());
    }


    @Test
    @DisplayName("transition CONFIRMED→COMPLETED — saves COMPLETED status and fires STATUS_CHANGED event")
    void transition_confirmedToCompleted_succeeds() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);
        TransitionRequest req = transitionReq(AppointmentStatus.COMPLETED, null);

        AppointmentResponse response = transitionService.transition(100L, req, DOCTOR_USER_ID);

        assertThat(response).isNotNull();
        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        verify(eventProducer).publishAppointmentEvent(
                argThat(e -> "STATUS_CHANGED_TO_COMPLETED".equals(e.getEventType())));
    }


    @Test
    @DisplayName("transition CONFIRMED→NO_SHOW — saves NO_SHOW status and fires STATUS_CHANGED event")
    void transition_confirmedToNoShow_succeeds() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);
        TransitionRequest req = transitionReq(AppointmentStatus.NO_SHOW, null);

        transitionService.transition(100L, req, DOCTOR_USER_ID);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
        verify(eventProducer).publishAppointmentEvent(
                argThat(e -> "STATUS_CHANGED_TO_NO_SHOW".equals(e.getEventType())));
    }


    @Test
    @DisplayName("transition CONFIRMED→CANCELLED — stores cancellationReason and fires event")
    void transition_confirmedToCancelled_setsCancellationReason() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);
        TransitionRequest req = transitionReq(AppointmentStatus.CANCELLED, "Doctor unavailable");

        transitionService.transition(100L, req, DOCTOR_USER_ID);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(captor.getValue().getCancellationReason()).isEqualTo("Doctor unavailable");
    }

    @Test
    @DisplayName("transition PENDING→CANCELLED — allowed (CANCELLED is valid from any non-COMPLETED state)")
    void transition_pendingToCancelled_succeeds() {
        appointment.setStatus(AppointmentStatus.PENDING);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);
        TransitionRequest req = transitionReq(AppointmentStatus.CANCELLED, "Patient no-show");

        AppointmentResponse response = transitionService.transition(100L, req, DOCTOR_USER_ID);
        assertThat(response).isNotNull();
    }


    @Test
    @DisplayName("transition PENDING→COMPLETED — throws INVALID_TRANSITION without saving")
    void transition_pendingToCompleted_throwsInvalidTransition() {
        appointment.setStatus(AppointmentStatus.PENDING);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        TransitionRequest req = transitionReq(AppointmentStatus.COMPLETED, null);

        assertThatThrownBy(() -> transitionService.transition(100L, req, DOCTOR_USER_ID))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("INVALID_TRANSITION"));
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    @DisplayName("transition PENDING→NO_SHOW — throws INVALID_TRANSITION")
    void transition_pendingToNoShow_throwsInvalidTransition() {
        appointment.setStatus(AppointmentStatus.PENDING);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        TransitionRequest req = transitionReq(AppointmentStatus.NO_SHOW, null);

        assertThatThrownBy(() -> transitionService.transition(100L, req, DOCTOR_USER_ID))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("INVALID_TRANSITION"));
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("transition COMPLETED→CANCELLED — throws INVALID_TRANSITION")
    void transition_completedToCancelled_throwsInvalidTransition() {
        appointment.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        TransitionRequest req = transitionReq(AppointmentStatus.CANCELLED, null);

        assertThatThrownBy(() -> transitionService.transition(100L, req, DOCTOR_USER_ID))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("transition CANCELLED→COMPLETED — throws INVALID_TRANSITION")
    void transition_cancelledToCompleted_throwsInvalidTransition() {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        TransitionRequest req = transitionReq(AppointmentStatus.COMPLETED, null);

        assertThatThrownBy(() -> transitionService.transition(100L, req, DOCTOR_USER_ID))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("transition NO_SHOW→COMPLETED — throws INVALID_TRANSITION")
    void transition_noShowToCompleted_throwsInvalidTransition() {
        appointment.setStatus(AppointmentStatus.NO_SHOW);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        TransitionRequest req = transitionReq(AppointmentStatus.COMPLETED, null);

        assertThatThrownBy(() -> transitionService.transition(100L, req, DOCTOR_USER_ID))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("INVALID_TRANSITION"));
    }


    @Test
    @DisplayName("transition — event type encodes the target status name exactly")
    void transition_eventTypeEncodeTargetStatus() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        transitionService.transition(100L, transitionReq(AppointmentStatus.COMPLETED, null), DOCTOR_USER_ID);

        verify(eventProducer).publishAppointmentEvent(
                argThat(e -> e.getEventType().equals("STATUS_CHANGED_TO_COMPLETED")
                        && e.getPatientId().equals(appointment.getPatient().getId())));
    }


    private TransitionRequest transitionReq(AppointmentStatus to, String reason) {
        TransitionRequest req = new TransitionRequest();
        req.setTo(to);
        req.setReason(reason);
        return req;
    }
}
