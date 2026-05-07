package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.config.entity.SystemConfig;
import com.medibook.config.repository.SystemConfigRepository;
import com.medibook.domain.appointment.dto.*;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.messaging.producer.AppointmentEventProducer;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private DoctorRepository doctorRepository;
    @Mock private UserRepository userRepository;
    @Mock private AppointmentEventProducer eventProducer;
    @Mock private AppointmentHoldService holdService;
    @Mock private SystemConfigRepository configRepository;

    @InjectMocks
    private AppointmentService appointmentService;

    private User patient;
    private Doctor doctor;
    private Appointment appointment;
    private UserPrincipal patientPrincipal;
    private UserPrincipal doctorPrincipal;

    @BeforeEach
    void setUp() {
        patient = User.builder().id(1L).email("pat@test.com").build();
        User doctorUser = User.builder().id(2L).email("doc@test.com").build();
        doctor = Doctor.builder().id(10L).user(doctorUser).isActive(true).build();
        
        appointment = Appointment.builder()
                .id(100L)
                .patient(patient)
                .doctor(doctor)
                .scheduledAt(LocalDateTime.now().plusDays(2))
                .endTime(LocalDateTime.now().plusDays(2).plusMinutes(30))
                .status(AppointmentStatus.PENDING)
                .build();

        patientPrincipal = UserPrincipal.builder().id(1L).build();
        doctorPrincipal = UserPrincipal.builder().id(2L).build();
    }

    @Test
    void book_success() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setHoldId("hold-123");
        req.setType(AppointmentType.IN_PERSON);

        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.existsConflict(any(), any(), any())).thenReturn(false);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);

        AppointmentResponse res = appointmentService.book(patient.getId(), req);

        assertNotNull(res);
        verify(holdService).validateHold(doctor.getId(), req.getScheduledAt(), "hold-123");
        verify(holdService).releaseHold(doctor.getId(), req.getScheduledAt());
        verify(eventProducer).publishAppointmentEvent(any());
    }

    @Test
    void book_conflict_throwsException() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));

        when(appointmentRepository.existsConflict(any(), any(), any())).thenReturn(true);

        MediBookException ex = assertThrows(MediBookException.class, () -> appointmentService.book(patient.getId(), req));
        assertEquals("SLOT_TAKEN", ex.getErrorCode());
    }

    @Test
    void book_dataIntegrityViolation_throwsConflict() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));

        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetails(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenThrow(DataIntegrityViolationException.class);

        MediBookException ex = assertThrows(MediBookException.class, () -> appointmentService.book(patient.getId(), req));
        assertEquals("SLOT_TAKEN", ex.getErrorCode());
    }

    @Test
    void cancel_byPatientWithinNotice_throws422() {
        appointment.setScheduledAt(LocalDateTime.now().plusHours(12)); // Within 24h
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        
        SystemConfig config = new SystemConfig("CANCELLATION_NOTICE_HOURS", "24", null, null);
        when(configRepository.findById("CANCELLATION_NOTICE_HOURS")).thenReturn(Optional.of(config));

        CancelRequest req = new CancelRequest();
        MediBookException ex = assertThrows(MediBookException.class, () -> appointmentService.cancel(100L, req, patientPrincipal));
        assertEquals("WITHIN_NOTICE_PERIOD", ex.getErrorCode());
    }

    @Test
    void cancel_byPatientOutsideNotice_success() {
        appointment.setScheduledAt(LocalDateTime.now().plusHours(48)); // Outside 24h
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(configRepository.findById("CANCELLATION_NOTICE_HOURS")).thenReturn(Optional.empty()); // defaults to 24
        when(appointmentRepository.save(any())).thenReturn(appointment);
        when(userRepository.getReferenceById(any())).thenReturn(patient);

        CancelRequest req = new CancelRequest();
        AppointmentResponse res = appointmentService.cancel(100L, req, patientPrincipal);
        assertEquals(AppointmentStatus.CANCELLED, res.getStatus());
    }

    @Test
    void reschedule_optimisticLockingFailure() {
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(LocalDateTime.now().plusDays(3));
        req.setNewEnd(LocalDateTime.now().plusDays(3).plusMinutes(30));

        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenThrow(OptimisticLockingFailureException.class);

        MediBookException ex = assertThrows(MediBookException.class, () -> appointmentService.reschedule(100L, req, patientPrincipal));
        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
    }

    @Test
    void generateIcs_success() {
        appointment.setConfirmationCode("MB-12345");
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        String ics = appointmentService.generateIcs(100L);
        assertTrue(ics.contains("BEGIN:VCALENDAR"));
        assertTrue(ics.contains("MB-12345@medibook.com"));
    }
}
