package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.config.entity.SystemConfig;
import com.medibook.config.repository.SystemConfigRepository;
import com.medibook.domain.appointment.dto.*;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.event.AuditEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import com.medibook.security.UserPrincipal;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AppointmentEventProducer eventProducer;
    private final AppointmentHoldService holdService;
    private final SystemConfigRepository configRepository;

    @Bulkhead(name = "appointmentService")
    @Transactional
    public AppointmentResponse book(Long patientId, AppointmentRequest request) {
        LocalDateTime endTime = request.getScheduledAt().plusMinutes(request.getDurationMins());

        holdService.validateHold(request.getDoctorId(), request.getScheduledAt(), request.getHoldId());

        if (appointmentRepository.existsConflict(request.getDoctorId(), request.getScheduledAt(), endTime)) {
            throw new MediBookException("Doctor is not available at the requested time",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }

        User patient = userRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", patientId));

        Doctor doctor = doctorRepository.findByIdWithDetails(request.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", request.getDoctorId()));

        if (!doctor.isActive()) {
            throw new MediBookException("Doctor is not currently accepting appointments",
                    HttpStatus.CONFLICT, "DOCTOR_INACTIVE");
        }

        String confirmationCode = "MB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        Appointment appointment = Appointment.builder()
                .patient(patient)
                .doctor(doctor)
                .department(doctor.getDepartment())
                .scheduledAt(request.getScheduledAt())
                .endTime(endTime)
                .durationMins(request.getDurationMins())
                .reason(request.getReason())
                .type(request.getType())
                .status(AppointmentStatus.PENDING)
                .confirmationCode(confirmationCode)
                .build();

        Appointment saved;
        try {
            saved = appointmentRepository.save(appointment);
        } catch (DataIntegrityViolationException ex) {
            throw new MediBookException("Doctor is not available at the requested time",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }

        if (request.getHoldId() != null) {
            final Long doctorId = request.getDoctorId();
            final java.time.LocalDateTime scheduledAt = request.getScheduledAt();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        holdService.releaseHold(doctorId, scheduledAt);
                    }
                });
            } else {
                holdService.releaseHold(doctorId, scheduledAt);
            }
        }

        eventProducer.publishAppointmentEvent(buildEvent(saved, "BOOKED"));
        eventProducer.publishAuditEvent(buildAuditEvent(saved, patientId, saved.getPatient().getEmail(), "APPOINTMENT_BOOKED"));

        log.info("Appointment [{}] booked by patient [{}] with doctor [{}]",
                saved.getId(), patientId, request.getDoctorId());

        return AppointmentResponse.fromEntity(saved);
    }

    @CacheEvict(value = "appointments", key = "#id")
    @Transactional
    public AppointmentResponse cancel(Long id, CancelRequest request, UserPrincipal principal) {
        Appointment appt = getAndValidate(id);

        if (appt.getStatus() == AppointmentStatus.COMPLETED || appt.getStatus() == AppointmentStatus.CANCELLED) {
            throw new MediBookException("Cannot cancel an appointment that is already " + appt.getStatus(),
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION");
        }

        boolean isAdmin = principal.hasRole("ROLE_ADMIN");
        boolean isPatient = appt.getPatient().getId().equals(principal.getId());
        
        if (!isAdmin && !isPatient) {
            throw new MediBookException("Not authorized to cancel this appointment",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (isPatient && !isAdmin) {
            CancellationPolicyResponse policy = getCancellationPolicy();
            LocalDateTime cutoff = appt.getScheduledAt().minusHours(policy.getNoticeHours());
            if (LocalDateTime.now().isAfter(cutoff)) {
                throw new MediBookException("Cancellation is within the " + policy.getNoticeHours() + "-hour notice period. A cancellation fee applies.",
                        HttpStatus.UNPROCESSABLE_ENTITY, "WITHIN_NOTICE_PERIOD");
            }
        }

        appt.setStatus(AppointmentStatus.CANCELLED);
        appt.setCancelledAt(LocalDateTime.now());
        appt.setCancelledBy(userRepository.getReferenceById(principal.getId()));
        appt.setCancellationReason(request.getReason());

        try {
            Appointment saved = appointmentRepository.save(appt);
            eventProducer.publishAppointmentEvent(buildEvent(saved, "CANCELLED"));
            eventProducer.publishAuditEvent(buildAuditEvent(saved, principal.getId(), principal.getEmail(), "APPOINTMENT_CANCELLED"));
            return AppointmentResponse.fromEntity(saved);
        } catch (OptimisticLockingFailureException ex) {
            throw new MediBookException("Appointment was modified concurrently. Please refresh.", HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        }
    }

    @CacheEvict(value = "appointments", key = "#id")
    @Transactional
    public AppointmentResponse reschedule(Long id, RescheduleRequest request, UserPrincipal principal) {
        Appointment appt = getAndValidate(id);

        if (!appt.getPatient().getId().equals(principal.getId())) {
            throw new MediBookException("Not authorized to reschedule this appointment", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (appt.getStatus() == AppointmentStatus.COMPLETED || appt.getStatus() == AppointmentStatus.CANCELLED) {
            throw new MediBookException("Cannot reschedule a completed or cancelled appointment", HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION");
        }

        holdService.validateHold(appt.getDoctor().getId(), request.getNewStart(), request.getHoldId());

        if (appointmentRepository.existsConflict(appt.getDoctor().getId(), request.getNewStart(), request.getNewEnd())) {
            throw new MediBookException("New slot is taken", HttpStatus.CONFLICT, "SLOT_TAKEN");
        }

        appt.setScheduledAt(request.getNewStart());
        appt.setEndTime(request.getNewEnd());
        appt.setDurationMins((int) java.time.Duration.between(request.getNewStart(), request.getNewEnd()).toMinutes());

        try {
            Appointment saved = appointmentRepository.save(appt);
            if (request.getHoldId() != null) {
                holdService.releaseHold(appt.getDoctor().getId(), request.getNewStart());
            }
            eventProducer.publishAppointmentEvent(buildEvent(saved, "RESCHEDULED"));
            eventProducer.publishAuditEvent(buildAuditEvent(saved, principal.getId(), principal.getEmail(), "APPOINTMENT_RESCHEDULED"));
            return AppointmentResponse.fromEntity(saved);
        } catch (OptimisticLockingFailureException ex) {
            throw new MediBookException("Appointment was modified concurrently.", HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        }
    }

    @Transactional(readOnly = true)
    public CancellationPolicyResponse getCancellationPolicy() {
        int noticeHours = configRepository.findById("CANCELLATION_NOTICE_HOURS")
                .map(config -> Integer.parseInt(config.getConfigValue()))
                .orElse(24);
        return CancellationPolicyResponse.builder()
                .noticeHours(noticeHours)
                .feeApplies(true)
                .build();
    }

    @Cacheable(value = "appointments", key = "#id")
    @Transactional(readOnly = true)
    public AppointmentResponse getById(Long id) {
        return appointmentRepository.findByIdWithDetails(id)
                .map(AppointmentResponse::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", id));
    }

    @Transactional(readOnly = true)
    public Page<AppointmentResponse> getUpcomingByPatient(Long patientId, Pageable pageable) {
        return appointmentRepository.findByPatientIdAndScheduledAtAfterOrderByScheduledAtAsc(patientId, LocalDateTime.now(), pageable)
                .map(AppointmentResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<AppointmentResponse> getPastByPatient(Long patientId, Pageable pageable) {
        return appointmentRepository.findByPatientIdAndScheduledAtBeforeOrderByScheduledAtDesc(patientId, LocalDateTime.now(), pageable)
                .map(AppointmentResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public String generateIcs(Long id) {
        Appointment appt = getAndValidate(id);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        
        return "BEGIN:VCALENDAR\n" +
                "VERSION:2.0\n" +
                "PRODID:-//MediBook//EN\n" +
                "BEGIN:VEVENT\n" +
                "UID:" + appt.getConfirmationCode() + "@medibook.com\n" +
                "DTSTAMP:" + LocalDateTime.now().format(dtf) + "\n" +
                "DTSTART:" + appt.getScheduledAt().format(dtf) + "\n" +
                "DTEND:" + appt.getEndTime().format(dtf) + "\n" +
                "SUMMARY:MediBook Appointment with Dr. " + appt.getDoctor().getUser().getFullName() + "\n" +
                "DESCRIPTION:Reason: " + appt.getReason() + "\n" +
                "END:VEVENT\n" +
                "END:VCALENDAR";
    }

    @Transactional(readOnly = true)
    public String getPatientPhone(Long patientId) {
        return userRepository.findById(patientId)
                .map(com.medibook.domain.user.entity.User::getPhone)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public void notify(Long id) {
        Appointment appt = getAndValidate(id);
        eventProducer.publishAppointmentEvent(buildEvent(appt, "NOTIFICATION_TRIGGERED"));
    }

    private Appointment getAndValidate(Long id) {
        return appointmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", id));
    }

    private AppointmentEvent buildEvent(Appointment a, String type) {
        return AppointmentEvent.builder()
                .eventType(type)
                .appointmentId(a.getId())
                .patientId(a.getPatient().getId())
                .patientEmail(a.getPatient().getEmail())
                .patientName(a.getPatient().getFullName())
                .doctorId(a.getDoctor().getId())
                .doctorEmail(a.getDoctor().getUser().getEmail())
                .doctorName(a.getDoctor().getUser().getFullName())
                .departmentName(a.getDoctor().getDepartment().getName())
                .scheduledAt(a.getScheduledAt())
                .status(a.getStatus())
                .build();
    }

    private AuditEvent buildAuditEvent(Appointment a, Long actorId, String actorEmail, String action) {
        return AuditEvent.builder()
                .action(action)
                .actorId(actorId)
                .actorEmail(actorEmail)
                .resourceType("Appointment")
                .resourceId(String.valueOf(a.getId()))
                .detail("Action on appointment " + a.getId())
                .build();
    }
}
