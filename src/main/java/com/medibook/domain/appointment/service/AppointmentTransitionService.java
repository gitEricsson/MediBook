package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.dto.TransitionRequest;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.emergency.service.EmergencySettlementService;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentTransitionService {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentEventProducer eventProducer;
    private final EmergencySettlementService emergencySettlementService;

    /**
     * Valid forward transitions per the full appointment lifecycle FSM.
     *
     * PENDING_PAYMENT → PENDING (on payment initiation)
     * PENDING         → CONFIRMED (payment webhook success)
     * CONFIRMED       → CHECKED_IN, CANCELLED, NO_SHOW
     * CHECKED_IN      → IN_WAITING_ROOM
     * IN_WAITING_ROOM → IN_CONSULTATION
     * IN_CONSULTATION → COMPLETED
     * COMPLETED       → REFUNDED (on approved refund saga)
     * CANCELLED       → REFUNDED (on approved refund saga)
     * EMERGENCY_PENDING_SETTLEMENT stays terminal until settled externally
     */
    private static final Map<AppointmentStatus, Set<AppointmentStatus>> ALLOWED_TRANSITIONS = Map.ofEntries(
            Map.entry(AppointmentStatus.PENDING_PAYMENT,           Set.of(AppointmentStatus.PENDING, AppointmentStatus.CANCELLED)),
            Map.entry(AppointmentStatus.PENDING,                   Set.of(AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED)),
            Map.entry(AppointmentStatus.CONFIRMED,                 Set.of(AppointmentStatus.CHECKED_IN, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)),
            Map.entry(AppointmentStatus.CHECKED_IN,                Set.of(AppointmentStatus.IN_WAITING_ROOM, AppointmentStatus.CANCELLED)),
            Map.entry(AppointmentStatus.IN_WAITING_ROOM,           Set.of(AppointmentStatus.IN_CONSULTATION, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)),
            Map.entry(AppointmentStatus.IN_CONSULTATION,           Set.of(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED)),
            Map.entry(AppointmentStatus.COMPLETED,                 Set.of(AppointmentStatus.REFUNDED)),
            Map.entry(AppointmentStatus.CANCELLED,                 Set.of(AppointmentStatus.REFUNDED)),
            Map.entry(AppointmentStatus.NO_SHOW,                   Set.of()),
            Map.entry(AppointmentStatus.REFUNDED,                  Set.of()),
            Map.entry(AppointmentStatus.EMERGENCY_PENDING_SETTLEMENT, Set.of(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED))
    );

    @CacheEvict(value = "appointments", key = "#id")
    @Transactional
    public AppointmentResponse transition(Long id, TransitionRequest request, Long doctorId) {
        Appointment appt = appointmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", id));

        if (!appt.getDoctor().getUser().getId().equals(doctorId)) {
            throw new MediBookException("Not authorized to transition this appointment", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        AppointmentStatus current = appt.getStatus();
        AppointmentStatus target = request.getTo();

        Set<AppointmentStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new MediBookException(
                    "Transition from " + current + " to " + target + " is not allowed.",
                    HttpStatus.BAD_REQUEST, "INVALID_TRANSITION");
        }

        if (target == AppointmentStatus.CANCELLED) {
            appt.setCancellationReason(request.getReason());
            appt.setCancelledAt(java.time.LocalDateTime.now());
        }

        appt.setStatus(target);
        Appointment saved = appointmentRepository.save(appt);

        String eventType = "STATUS_CHANGED_TO_" + target.name();
        AppointmentEvent event = AppointmentEvent.builder()
                .eventType(eventType)
                .appointmentId(saved.getId())
                .patientId(saved.getPatient().getId())
                .patientEmail(saved.getPatient().getEmail())
                .patientName(saved.getPatient().getFullName())
                .status(saved.getStatus())
                .build();

        final Long savedId = saved.getId();
        final AppointmentStatus prevStatus = current;
        afterCommit(() -> {
            eventProducer.publishAppointmentEvent(event);
            if (prevStatus == AppointmentStatus.EMERGENCY_PENDING_SETTLEMENT
                    && target == AppointmentStatus.COMPLETED) {
                emergencySettlementService.generateOutstandingInvoice(savedId);
            }
        });
        log.info("Appointment [{}] transitioned {} → {} by doctor [{}]", id, current, target, doctorId);
        return AppointmentResponse.fromEntity(saved);
    }

    /**
     * Internal system-level transition (e.g. payment webhooks, refund sagas).
     * No doctor ownership check — caller is responsible for authorization.
     */
    @CacheEvict(value = "appointments", key = "#id")
    @Transactional
    public AppointmentResponse systemTransition(Long id, AppointmentStatus target, String reason) {
        Appointment appt = appointmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", id));

        AppointmentStatus current = appt.getStatus();
        Set<AppointmentStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new MediBookException(
                    "System transition from " + current + " to " + target + " is not allowed.",
                    HttpStatus.BAD_REQUEST, "INVALID_TRANSITION");
        }

        if (target == AppointmentStatus.CANCELLED && reason != null) {
            appt.setCancellationReason(reason);
            appt.setCancelledAt(java.time.LocalDateTime.now());
        }

        appt.setStatus(target);
        Appointment saved = appointmentRepository.save(appt);

        AppointmentEvent event = AppointmentEvent.builder()
                .eventType("STATUS_CHANGED_TO_" + target.name())
                .appointmentId(saved.getId())
                .patientId(saved.getPatient().getId())
                .patientEmail(saved.getPatient().getEmail())
                .patientName(saved.getPatient().getFullName())
                .status(saved.getStatus())
                .build();

        afterCommit(() -> eventProducer.publishAppointmentEvent(event));
        log.info("Appointment [{}] system-transitioned {} → {} reason={}", id, current, target, reason);
        return AppointmentResponse.fromEntity(saved);
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
}
