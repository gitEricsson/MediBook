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
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.Role;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
        patient = User.builder().id(1L).email("pat@test.com").role(Role.ROLE_PATIENT).build();
        User doctorUser = User.builder().id(2L).email("doc@test.com").role(Role.ROLE_DOCTOR).build();
        Department department = Department.builder().id(1L).name("General Medicine").build();
        doctor = Doctor.builder().id(10L).user(doctorUser).department(department).isActive(true).build();
        
        appointment = Appointment.builder()
                .id(100L)
                .patient(patient)
                .doctor(doctor)
                .scheduledAt(LocalDateTime.now().plusDays(2))
                .endTime(LocalDateTime.now().plusDays(2).plusMinutes(30))
                .status(AppointmentStatus.PENDING)
                .build();

        patientPrincipal = UserPrincipal.fromUser(patient);
        doctorPrincipal = UserPrincipal.fromUser(doctorUser);

        lenient().when(userRepository.getReferenceById(anyLong())).thenReturn(patient);
    }

    @Test
    void book_success() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setHoldId("hold-123");
        req.setType(AppointmentType.IN_PERSON);

        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
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

        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
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
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
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
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.existsConflictExcluding(anyLong(), anyLong(), any(), any())).thenReturn(false);
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


    @Test
    void book_holdValidationFailure_propagatesException() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setHoldId("stale-hold");
        doThrow(new MediBookException("Hold expired", HttpStatus.BAD_REQUEST, "HOLD_EXPIRED"))
                .when(holdService).validateHold(eq(doctor.getId()), any(), eq("stale-hold"));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.book(patient.getId(), req));
        assertEquals("HOLD_EXPIRED", ex.getErrorCode());
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void book_patientNotFound_throwsNotFound() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        when(userRepository.findById(patient.getId())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> appointmentService.book(patient.getId(), req));
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void book_doctorNotFound_throwsNotFound() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(99L);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetailsForUpdate(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> appointmentService.book(patient.getId(), req));
    }

    @Test
    void book_inactiveDoctor_throwsDoctorInactive() {
        Doctor inactive = Doctor.builder().id(10L).user(doctor.getUser())
                .department(doctor.getDepartment()).isActive(false).licenseNumber("LIC-X").build();
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(inactive.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetailsForUpdate(inactive.getId())).thenReturn(Optional.of(inactive));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.book(patient.getId(), req));
        assertEquals("DOCTOR_INACTIVE", ex.getErrorCode());
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void book_savedEntityHasConfirmationCodeDepartmentTypeAndEndTime() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setType(AppointmentType.TELEHEALTH);
        req.setDurationMins(45);
        when(appointmentRepository.existsConflict(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);

        appointmentService.book(patient.getId(), req);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        Appointment saved = captor.getValue();
        assertNotNull(saved.getConfirmationCode());
        assertTrue(saved.getConfirmationCode().startsWith("MB-"));
        assertEquals(AppointmentType.TELEHEALTH, saved.getType());
        assertEquals(AppointmentStatus.PENDING, saved.getStatus());
        assertEquals(doctor.getDepartment(), saved.getDepartment());
        assertNotNull(saved.getEndTime());
        assertEquals(req.getScheduledAt().plusMinutes(45), saved.getEndTime());
    }

    @Test
    void book_withHoldId_releasesHoldAfterSave() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setHoldId("hold-abc");
        when(appointmentRepository.existsConflict(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.book(patient.getId(), req);

        verify(holdService).releaseHold(eq(doctor.getId()), any(LocalDateTime.class));
    }

    @Test
    void book_withoutHoldId_doesNotCallReleaseHold() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        when(appointmentRepository.existsConflict(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.book(patient.getId(), req);

        verify(holdService, never()).releaseHold(any(), any());
    }

    @Test
    void book_publishesBookedAndAuditEvents() {
        AppointmentRequest req = new AppointmentRequest();
        req.setDoctorId(doctor.getId());
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        when(appointmentRepository.existsConflict(any(), any(), any())).thenReturn(false);
        when(userRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.book(patient.getId(), req);

        verify(eventProducer).publishAppointmentEvent(argThat(e -> "BOOKED".equals(e.getEventType())));
        verify(eventProducer).publishAuditEvent(argThat(e -> "APPOINTMENT_BOOKED".equals(e.getAction())));
    }


    @Test
    void cancel_alreadyCancelled_throwsBadRequest() {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.cancel(100L, new CancelRequest(), patientPrincipal));
        assertEquals("INVALID_STATUS_TRANSITION", ex.getErrorCode());
    }

    @Test
    void cancel_alreadyCompleted_throwsBadRequest() {
        appointment.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.cancel(100L, new CancelRequest(), patientPrincipal));
        assertEquals("INVALID_STATUS_TRANSITION", ex.getErrorCode());
    }

    @Test
    void cancel_unrelatedUser_throwsForbidden() {
        User stranger = User.builder().id(50L).email("stranger@test.com").role(Role.ROLE_PATIENT).build();
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.cancel(100L, new CancelRequest(), UserPrincipal.fromUser(stranger)));
        assertEquals("ACCESS_DENIED", ex.getErrorCode());
    }

    @Test
    void cancel_byAdmin_bypassesNoticePeriodCheck() {
        appointment.setScheduledAt(LocalDateTime.now().plusMinutes(30));  // inside 24h window
        User admin = User.builder().id(99L).email("admin@test.com").role(Role.ROLE_ADMIN).build();
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(userRepository.getReferenceById(99L)).thenReturn(admin);
        when(appointmentRepository.save(any())).thenReturn(appointment);

        AppointmentResponse response = appointmentService.cancel(100L, new CancelRequest(), UserPrincipal.fromUser(admin));
        assertNotNull(response);
        verify(configRepository, never()).findById(any());   // policy never queried for admin
    }

    @Test
    void cancel_setsAuditFieldsAndCancellationReason() {
        appointment.setScheduledAt(LocalDateTime.now().plusDays(5));
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(configRepository.findById("CANCELLATION_NOTICE_HOURS")).thenReturn(Optional.empty());
        when(appointmentRepository.save(any())).thenReturn(appointment);
        CancelRequest req = new CancelRequest();
        req.setReason("Personal emergency");

        appointmentService.cancel(100L, req, patientPrincipal);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        Appointment saved = captor.getValue();
        assertEquals(AppointmentStatus.CANCELLED, saved.getStatus());
        assertNotNull(saved.getCancelledAt());
        assertNotNull(saved.getCancelledBy());
        assertEquals("Personal emergency", saved.getCancellationReason());
    }

    @Test
    void cancel_optimisticLockingFailure_throwsConcurrentModification() {
        appointment.setScheduledAt(LocalDateTime.now().plusDays(5));
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(configRepository.findById("CANCELLATION_NOTICE_HOURS")).thenReturn(Optional.empty());
        when(appointmentRepository.save(any())).thenThrow(OptimisticLockingFailureException.class);

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.cancel(100L, new CancelRequest(), patientPrincipal));
        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
    }

    @Test
    void cancel_publishesBothCancelledAndAuditEvents() {
        appointment.setScheduledAt(LocalDateTime.now().plusDays(5));
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(configRepository.findById("CANCELLATION_NOTICE_HOURS")).thenReturn(Optional.empty());
        when(appointmentRepository.save(any())).thenReturn(appointment);

        appointmentService.cancel(100L, new CancelRequest(), patientPrincipal);

        verify(eventProducer).publishAppointmentEvent(argThat(e -> "CANCELLED".equals(e.getEventType())));
        verify(eventProducer).publishAuditEvent(argThat(e -> "APPOINTMENT_CANCELLED".equals(e.getAction())));
    }


    @Test
    void getCancellationPolicy_readsNoticeHoursFromSystemConfig() {
        when(configRepository.findById("CANCELLATION_NOTICE_HOURS"))
                .thenReturn(Optional.of(new SystemConfig("CANCELLATION_NOTICE_HOURS", "48", null, null)));

        CancellationPolicyResponse policy = appointmentService.getCancellationPolicy();
        assertEquals(48, policy.getNoticeHours());
        assertTrue(policy.isFeeApplies());
    }

    @Test
    void getCancellationPolicy_defaultsTo24WhenConfigAbsent() {
        when(configRepository.findById("CANCELLATION_NOTICE_HOURS")).thenReturn(Optional.empty());

        CancellationPolicyResponse policy = appointmentService.getCancellationPolicy();
        assertEquals(24, policy.getNoticeHours());
        assertTrue(policy.isFeeApplies());
    }


    @Test
    void reschedule_notPatient_throwsForbidden() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(LocalDateTime.now().plusDays(3));
        req.setNewEnd(LocalDateTime.now().plusDays(3).plusMinutes(30));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.reschedule(100L, req, doctorPrincipal));
        assertEquals("ACCESS_DENIED", ex.getErrorCode());
    }

    @Test
    void reschedule_completedAppointment_throwsBadRequest() {
        appointment.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(LocalDateTime.now().plusDays(3));
        req.setNewEnd(LocalDateTime.now().plusDays(3).plusMinutes(30));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.reschedule(100L, req, patientPrincipal));
        assertEquals("INVALID_STATUS_TRANSITION", ex.getErrorCode());
    }

    @Test
    void reschedule_cancelledAppointment_throwsBadRequest() {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(LocalDateTime.now().plusDays(3));
        req.setNewEnd(LocalDateTime.now().plusDays(3).plusMinutes(30));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.reschedule(100L, req, patientPrincipal));
        assertEquals("INVALID_STATUS_TRANSITION", ex.getErrorCode());
    }

    @Test
    void reschedule_newSlotConflict_throwsSlotTaken() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        LocalDateTime newStart = LocalDateTime.now().plusDays(3);
        LocalDateTime newEnd = newStart.plusMinutes(30);
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.existsConflictExcluding(100L, doctor.getId(), newStart, newEnd)).thenReturn(true);
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(newStart);
        req.setNewEnd(newEnd);

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.reschedule(100L, req, patientPrincipal));
        assertEquals("SLOT_TAKEN", ex.getErrorCode());
    }

    @Test
    void reschedule_success_updatesTimesCalculatesDurationAndPublishesEvents() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        LocalDateTime newStart = LocalDateTime.now().plusDays(3);
        LocalDateTime newEnd = newStart.plusMinutes(45);
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.existsConflictExcluding(anyLong(), anyLong(), any(), any())).thenReturn(false);
        when(appointmentRepository.save(any())).thenReturn(appointment);
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(newStart);
        req.setNewEnd(newEnd);

        AppointmentResponse response = appointmentService.reschedule(100L, req, patientPrincipal);
        assertNotNull(response);

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertEquals(newStart, captor.getValue().getScheduledAt());
        assertEquals(newEnd, captor.getValue().getEndTime());
        assertEquals(45, captor.getValue().getDurationMins());
        verify(eventProducer).publishAppointmentEvent(argThat(e -> "RESCHEDULED".equals(e.getEventType())));
        verify(eventProducer).publishAuditEvent(argThat(e -> "APPOINTMENT_RESCHEDULED".equals(e.getAction())));
    }

    @Test
    void reschedule_optimisticLockingOnNewSlot_throwsConcurrentModification() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(doctorRepository.findByIdWithDetailsForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.existsConflictExcluding(anyLong(), anyLong(), any(), any())).thenReturn(false);
        when(appointmentRepository.save(any())).thenThrow(OptimisticLockingFailureException.class);
        RescheduleRequest req = new RescheduleRequest();
        req.setNewStart(LocalDateTime.now().plusDays(3));
        req.setNewEnd(LocalDateTime.now().plusDays(3).plusMinutes(30));

        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.reschedule(100L, req, patientPrincipal));
        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
    }


    @Test
    void getById_existing_returnsResponse() {
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        AppointmentResponse response = appointmentService.getById(100L);
        assertNotNull(response);
        assertEquals(100L, response.getId());
    }

    @Test
    void getById_notFound_throwsResourceNotFoundException() {
        when(appointmentRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> appointmentService.getById(999L));
    }

    @Test
    void getUpcomingByPatient_returnsMappedPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(appointmentRepository.findByPatientIdAndScheduledAtAfterOrderByScheduledAtAsc(
                eq(patient.getId()), any(LocalDateTime.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(appointment), pageable, 1));

        Page<AppointmentResponse> result = appointmentService.getUpcomingByPatient(patient.getId(), pageable);
        assertEquals(1, result.getTotalElements());
        assertEquals(100L, result.getContent().get(0).getId());
    }

    @Test
    void getPastByPatient_returnsMappedPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(appointmentRepository.findByPatientIdAndScheduledAtBeforeOrderByScheduledAtDesc(
                eq(patient.getId()), any(LocalDateTime.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(appointment), pageable, 1));

        Page<AppointmentResponse> result = appointmentService.getPastByPatient(patient.getId(), pageable);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void getByPatientCursor_upcoming_returnsCursorPage() {
        appointment.setScheduledAt(LocalDateTime.now().plusDays(2));
        when(appointmentRepository.findUpcomingByPatientCursor(
                eq(patient.getId()), any(LocalDateTime.class), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(List.of(appointment));

        var page = appointmentService.getByPatientCursor(patient.getId(), "upcoming", null, 20);

        assertEquals(1, page.getItems().size());
        assertFalse(page.isHasMore());
    }

    @Test
    void getByPatientCursor_invalidCursor_throwsBadRequest() {
        MediBookException ex = assertThrows(MediBookException.class,
                () -> appointmentService.getByPatientCursor(patient.getId(), "past", "%%%bad%%%", 20));

        assertEquals("INVALID_CURSOR", ex.getErrorCode());
    }


    @Test
    void generateIcs_notFound_throwsResourceNotFoundException() {
        when(appointmentRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> appointmentService.generateIcs(999L));
    }

    @Test
    void generateIcs_containsAllRequiredVcalendarFields() {
        appointment.setConfirmationCode("MB-ABCDEF");
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));

        String ics = appointmentService.generateIcs(100L);
        assertTrue(ics.contains("BEGIN:VCALENDAR"));
        assertTrue(ics.contains("VERSION:2.0"));
        assertTrue(ics.contains("BEGIN:VEVENT"));
        assertTrue(ics.contains("UID:MB-ABCDEF@medibook.com"));
        assertTrue(ics.contains("DTSTART:"));
        assertTrue(ics.contains("DTEND:"));
        assertTrue(ics.contains("SUMMARY:MediBook Appointment"));
        assertTrue(ics.contains("END:VEVENT"));
        assertTrue(ics.contains("END:VCALENDAR"));
    }
}
