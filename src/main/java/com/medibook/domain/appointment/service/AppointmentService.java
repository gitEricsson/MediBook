package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.common.response.CursorPageResponse;
import com.medibook.config.repository.SystemConfigRepository;
import com.medibook.domain.appointment.dto.*;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.payment.service.CancellationRefundService;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.service.DoctorScheduleService;
import com.medibook.domain.schedule.service.DoctorLeaveService;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.event.AuditEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import com.medibook.security.UserPrincipal;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
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
    private final DoctorLeaveService doctorLeaveService;
    private final AppointmentSchedulingPolicy schedulingPolicy;
    private final DoctorScheduleService doctorScheduleService;
    private final AppointmentPricingService pricingService;
    private final com.medibook.domain.patient.service.AccessGrantService accessGrantService;
    private final CancellationRefundService cancellationRefundService;
    private final com.medibook.domain.payment.repository.InvoiceRepository invoiceRepository;
    private final MeterRegistry meterRegistry;

    /**
     * Computes the outstanding balance for an appointment, if any. Returns the
     * invoice total when an UNPAID invoice exists; null otherwise. Surfaced to
     * the FE so the consultation-card "Pay outstanding bill" CTA can render
     * regardless of appointment status (e.g. COMPLETED emergency appointments
     * awaiting post-consult settlement).
     */
    private java.math.BigDecimal computeOutstandingBalance(Long appointmentId) {
        return invoiceRepository.findByAppointmentId(appointmentId)
                .filter(inv -> !"PAID".equalsIgnoreCase(inv.getStatus()))
                .map(com.medibook.domain.payment.entity.Invoice::getTotal)
                .orElse(null);
    }

    @Bulkhead(name = "appointmentService")
    @Transactional
    public AppointmentResponse book(Long patientId, AppointmentRequest request) {
        LocalDateTime endTime = request.getScheduledAt().plusMinutes(request.getDurationMins());

        holdService.validateHold(request.getDoctorId(), request.getScheduledAt(), request.getHoldId());

        // Single gate covering: past, doctor existence/active, leave, working-hours fit,
        // and overlap. Same policy as the hold check, run again here in case anything
        // changed during the hold window.
        schedulingPolicy.checkBookableWithOverlap(request.getDoctorId(), request.getScheduledAt(), endTime);

        // FOLLOW_UP consultations require explicit patient consent to share prior records.
        if (request.getConsultationType() == AppointmentType.FOLLOW_UP
                && !request.isFollowUpConsentGiven()) {
            throw new MediBookException(
                    "Patient consent is required to share prior medical records for a follow-up consultation.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "FOLLOW_UP_CONSENT_REQUIRED");
        }

        User patient = userRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", patientId));

        Doctor doctor = doctorRepository.findByIdWithDetailsForUpdate(request.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", request.getDoctorId()));

        String confirmationCode = "MB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        ConsultationMedium medium =
                request.getConsultationMedium() != null
                        ? request.getConsultationMedium()
                        : ConsultationMedium.PHYSICAL;
        AppointmentType consultationType =
                request.getConsultationType() != null
                        ? request.getConsultationType()
                        : AppointmentType.FIRST_VISIT;

        java.math.BigDecimal consultationFee = pricingService.computeFee(doctor, consultationType, medium);

        Appointment appointment = Appointment.builder()
                .patient(patient)
                .doctor(doctor)
                .department(doctor.getDepartment())
                .scheduledAt(request.getScheduledAt())
                .endTime(endTime)
                .durationMins(request.getDurationMins())
                .reason(request.getReason())
                .type(request.getType())
                .consultationMedium(medium)
                .consultationType(consultationType)
                .followUpConsentGiven(request.isFollowUpConsentGiven())
                .status(AppointmentStatus.PENDING)
                .confirmationCode(confirmationCode)
                .consultationFee(consultationFee)
                .build();

        Appointment saved;
        try {
            saved = appointmentRepository.save(appointment);
        } catch (DataIntegrityViolationException ex) {
            throw new MediBookException("Doctor is not available for booking at this time",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }

        // FOLLOW_UP + consent → upsert a time-bounded access grant so the
        // booked doctor can view consultation notes created on or before the
        // appointment date. The grant is APPROVED automatically because the
        // patient's consent was captured in the same booking action.
        if (consultationType == AppointmentType.FOLLOW_UP && request.isFollowUpConsentGiven()) {
            accessGrantService.upsertFollowUpGrant(
                    patientId,
                    doctor.getId(),
                    request.getScheduledAt().toLocalDate());
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

        doctorScheduleService.evictSlotCache(request.getDoctorId(), request.getScheduledAt().toLocalDate());

        AppointmentEvent appointmentEvent = buildEvent(saved, "BOOKED");
        AuditEvent auditEvent = buildAuditEvent(saved, patientId, saved.getPatient().getEmail(), "APPOINTMENT_BOOKED");
        eventProducer.publishAppointmentEvent(appointmentEvent);
        eventProducer.publishAuditEvent(auditEvent);

        log.info("Appointment [{}] booked by patient [{}] with doctor [{}]",
                saved.getId(), patientId, request.getDoctorId());

        meterRegistry.counter("appointments.created",
                "type", saved.getConsultationType() != null ? saved.getConsultationType().name() : "UNKNOWN",
                "medium", saved.getConsultationMedium() != null ? saved.getConsultationMedium().name() : "UNKNOWN"
        ).increment();

        return AppointmentResponse.fromEntity(saved);
    }

    @CacheEvict(value = "appointments", key = "#id")
    @Transactional
    public AppointmentResponse cancel(Long id, CancelRequest request, UserPrincipal principal) {
        Appointment appt = getAndValidate(id);

        if (appt.getStatus() == AppointmentStatus.COMPLETED
                || appt.getStatus() == AppointmentStatus.CANCELLED
                || appt.getStatus() == AppointmentStatus.NO_SHOW
                || appt.getStatus() == AppointmentStatus.REFUNDED
                || appt.getStatus() == AppointmentStatus.IN_CONSULTATION) {
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
            LocalDateTime cutoff = appt.getScheduledAt().minusMinutes(policy.getNoticeMinutes());
            if (LocalDateTime.now().isAfter(cutoff)) {
                throw new MediBookException(
                        "Cancellation is no longer possible — the appointment starts in under "
                                + policy.getNoticeMinutes() + " minutes.",
                        HttpStatus.UNPROCESSABLE_ENTITY, "WITHIN_NOTICE_PERIOD");
            }
        }

        appt.setStatus(AppointmentStatus.CANCELLED);
        appt.setCancelledAt(LocalDateTime.now());
        appt.setCancelledBy(userRepository.getReferenceById(principal.getId()));
        appt.setCancellationReason(request.getReason());

        try {
            Appointment saved = appointmentRepository.save(appt);
            doctorScheduleService.evictSlotCache(saved.getDoctor().getId(), saved.getScheduledAt().toLocalDate());
            AppointmentEvent appointmentEvent = buildEvent(saved, "CANCELLED");
            AuditEvent auditEvent = buildAuditEvent(saved, principal.getId(), principal.getEmail(), "APPOINTMENT_CANCELLED");
            eventProducer.publishAppointmentEvent(appointmentEvent);
            eventProducer.publishAuditEvent(auditEvent);
            // Schedule async refund if a successful payment exists for this appointment.
            cancellationRefundService.scheduleRefundIfPaid(saved.getId());
            meterRegistry.counter("appointments.cancelled",
                    "by", isAdmin ? "admin" : "patient"
            ).increment();
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
        if (appt.getStatus() == AppointmentStatus.COMPLETED
                || appt.getStatus() == AppointmentStatus.CANCELLED
                || appt.getStatus() == AppointmentStatus.IN_CONSULTATION
                || appt.getStatus() == AppointmentStatus.REFUNDED
                || appt.getStatus() == AppointmentStatus.EMERGENCY_PENDING_SETTLEMENT) {
            throw new MediBookException("Cannot reschedule a completed or cancelled appointment", HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION");
        }

        if (!request.getNewStart().isBefore(request.getNewEnd())) {
            throw new MediBookException("New end time must be after new start time",
                    HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE");
        }

        Long doctorId = appt.getDoctor().getId();
        doctorRepository.findByIdWithDetailsForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));

        holdService.validateHold(doctorId, request.getNewStart(), request.getHoldId());
        // Overlap is checked with an "excluding self" variant below so the current
        // appointment's own row doesn't trip the conflict guard.
        schedulingPolicy.checkBookable(doctorId, request.getNewStart());

        if (appointmentRepository.existsConflictExcluding(appt.getId(), doctorId, request.getNewStart(), request.getNewEnd())) {
            throw new MediBookException("Doctor is not available for booking at this time",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }

        appt.setScheduledAt(request.getNewStart());
        appt.setEndTime(request.getNewEnd());
        appt.setDurationMins((int) java.time.Duration.between(request.getNewStart(), request.getNewEnd()).toMinutes());

        try {
            Appointment saved = appointmentRepository.save(appt);
            if (request.getHoldId() != null) {
                final java.time.LocalDateTime scheduledAt = request.getNewStart();
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
            AppointmentEvent appointmentEvent = buildEvent(saved, "RESCHEDULED");
            AuditEvent auditEvent = buildAuditEvent(saved, principal.getId(), principal.getEmail(), "APPOINTMENT_RESCHEDULED");
            eventProducer.publishAppointmentEvent(appointmentEvent);
            eventProducer.publishAuditEvent(auditEvent);
            return AppointmentResponse.fromEntity(saved);
        } catch (OptimisticLockingFailureException ex) {
            throw new MediBookException("Appointment was modified concurrently.", HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        }
    }

    @Transactional(readOnly = true)
    public CancellationPolicyResponse getCancellationPolicy() {
        int noticeMinutes = configRepository.findById("CANCELLATION_NOTICE_MINUTES")
                .map(config -> Integer.parseInt(config.getConfigValue()))
                .orElse(30);
        return CancellationPolicyResponse.builder()
                .noticeMinutes(noticeMinutes)
                .feeApplies(false)
                .build();
    }

    @Cacheable(value = "appointments", key = "#id")
    @Transactional(readOnly = true)
    public AppointmentResponse getById(Long id) {
        return appointmentRepository.findByIdWithDetails(id)
                .map(a -> AppointmentResponse.fromEntity(a, computeOutstandingBalance(a.getId())))
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", id));
    }

    @Transactional(readOnly = true)
    public AppointmentResponse getByIdForDoctor(Long id, UserPrincipal principal) {
        Appointment appointment = getAndValidate(id);
        ensureDoctorOwnsAppointment(appointment, principal);
        return AppointmentResponse.fromEntity(appointment, computeOutstandingBalance(appointment.getId()));
    }

    @Transactional(readOnly = true)
    public String getPatientPhoneForDoctor(Long appointmentId, UserPrincipal principal) {
        Appointment appointment = getAndValidate(appointmentId);
        ensureDoctorOwnsAppointment(appointment, principal);
        return appointment.getPatient().getPhone();
    }

    @Transactional(readOnly = true)
    public void ensureDoctorCanAccessPatient(Long doctorUserId, Long patientId) {
        if (!appointmentRepository.existsDoctorPatientRelationship(doctorUserId, patientId)) {
            throw new MediBookException("Not authorized to access this patient",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }

    @Transactional(readOnly = true)
    public Page<AppointmentResponse> getUpcomingByPatient(Long patientId, Pageable pageable) {
        return appointmentRepository.findByPatientIdAndScheduledAtAfterOrderByScheduledAtAsc(patientId, LocalDateTime.now(), pageable)
                .map(a -> AppointmentResponse.fromEntity(a, computeOutstandingBalance(a.getId())));
    }

    @Transactional(readOnly = true)
    public Page<AppointmentResponse> getPastByPatient(Long patientId, Pageable pageable) {
        return appointmentRepository.findByPatientIdAndScheduledAtBeforeOrderByScheduledAtDesc(patientId, LocalDateTime.now(), pageable)
                .map(a -> AppointmentResponse.fromEntity(a, computeOutstandingBalance(a.getId())));
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<AppointmentResponse> getByPatientCursor(
            Long patientId,
            String tab,
            String cursor,
            Integer limit) {
        CursorPosition cursorPosition = decodeCursor(cursor);
        int pageSize = normalizeCursorLimit(limit);
        LocalDateTime anchor = LocalDateTime.now();
        List<Appointment> appointments;

        if ("past".equalsIgnoreCase(tab)) {
            appointments = appointmentRepository.findPastByPatientCursor(
                    patientId,
                    anchor,
                    cursorPosition == null ? null : cursorPosition.scheduledAt(),
                    cursorPosition == null ? null : cursorPosition.appointmentId(),
                    PageRequest.of(0, pageSize + 1));
        } else {
            appointments = appointmentRepository.findUpcomingByPatientCursor(
                    patientId,
                    anchor,
                    cursorPosition == null ? null : cursorPosition.scheduledAt(),
                    cursorPosition == null ? null : cursorPosition.appointmentId(),
                    PageRequest.of(0, pageSize + 1));
        }

        boolean hasMore = appointments.size() > pageSize;
        List<Appointment> pageItems = hasMore ? appointments.subList(0, pageSize) : appointments;
        List<AppointmentResponse> items = pageItems.stream()
                .map(a -> AppointmentResponse.fromEntity(a, computeOutstandingBalance(a.getId())))
                .toList();

        String nextCursor = null;
        if (hasMore && !pageItems.isEmpty()) {
            Appointment last = pageItems.getLast();
            nextCursor = encodeCursor(last.getScheduledAt(), last.getId());
        }

        return AppointmentCursorPageResponse.of(items, nextCursor, hasMore, pageSize);
    }

    @Transactional(readOnly = true)
    public String generateIcs(Long id) {
        Appointment appt = getAndValidate(id);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        
        return "BEGIN:VCALENDAR\n" +
                "VERSION:2.0\n" +
                "PRODID:-//MediBook//EN\n" +
                "BEGIN:VEVENT\n" +
                "UID:" + appt.getConfirmationCode() + "@medibook.com\n" +
                "DTSTAMP:" + LocalDateTime.now().format(dtf) + "\n" +
                "DTSTART:" + appt.getScheduledAt().format(dtf) + "\n" +
                "DTEND:" + appt.getEndTime().format(dtf) + "\n" +
                "SUMMARY:" + escapeIcsText("MediBook Appointment with Dr. " + appt.getDoctor().getUser().getFullName()) + "\n" +
                "DESCRIPTION:" + escapeIcsText("Reason: " + (appt.getReason() == null ? "" : appt.getReason())) + "\n" +
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

    private void ensureDoctorOwnsAppointment(Appointment appointment, UserPrincipal principal) {
        if (!appointment.getDoctor().getUser().getId().equals(principal.getId())) {
            throw new MediBookException("Not authorized to access this appointment",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }

    private AppointmentEvent buildEvent(Appointment a, String type) {
        return AppointmentEvent.builder()
                .eventType(type)
                .appointmentId(a.getId())
                .patientId(a.getPatient().getId())
                .patientEmail(a.getPatient().getEmail())
                .patientName(a.getPatient().getFullName())
                .doctorId(a.getDoctor().getUser().getId())
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

    private String escapeIcsText(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace(",", "\\,")
                .replace(";", "\\;");
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private int normalizeCursorLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        return Math.min(Math.max(limit, 1), 50);
    }

    private String encodeCursor(LocalDateTime scheduledAt, Long appointmentId) {
        String raw = "%s|%s".formatted(scheduledAt, appointmentId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new CursorPosition(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (RuntimeException ex) {
            throw new MediBookException("Invalid cursor value", HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }
    }

    private record CursorPosition(LocalDateTime scheduledAt, Long appointmentId) {
    }
}
