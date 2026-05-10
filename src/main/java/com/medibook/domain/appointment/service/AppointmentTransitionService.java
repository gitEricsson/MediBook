package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.dto.TransitionRequest;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentTransitionService {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentEventProducer eventProducer;

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

        if (target == AppointmentStatus.CONFIRMED) {
            if (current != AppointmentStatus.PENDING) {
                throw new MediBookException("Only PENDING appointments can be confirmed", HttpStatus.BAD_REQUEST, "INVALID_TRANSITION");
            }
        } else if (target == AppointmentStatus.COMPLETED || target == AppointmentStatus.NO_SHOW) {
            if (current != AppointmentStatus.CONFIRMED) {
                throw new MediBookException("Only CONFIRMED appointments can be marked as " + target, HttpStatus.BAD_REQUEST, "INVALID_TRANSITION");
            }
        } else if (target == AppointmentStatus.CANCELLED) {
            if (current == AppointmentStatus.COMPLETED) {
                throw new MediBookException("Cannot cancel a completed appointment", HttpStatus.BAD_REQUEST, "INVALID_TRANSITION");
            }
            appt.setCancellationReason(request.getReason());
        } else {
            throw new MediBookException("Unsupported target status: " + target, HttpStatus.BAD_REQUEST, "INVALID_TRANSITION");
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
