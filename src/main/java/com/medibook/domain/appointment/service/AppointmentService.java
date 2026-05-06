package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.security.UserPrincipal;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.AppointmentResponse;
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
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AppointmentEventProducer eventProducer;

    @Bulkhead(name = "appointmentService")
    @Transactional
    public AppointmentResponse book(Long patientId, AppointmentRequest request) {
        // Conflict check
        if (appointmentRepository.existsConflict(request.getDoctorId(), request.getScheduledAt())) {
            throw new MediBookException("Doctor is not available at the requested time",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }

        User patient = userRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", patientId));

        Doctor doctor = doctorRepository.findByIdWithDetails(request.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", request.getDoctorId()));

        Appointment appointment = Appointment.builder()
                .patient(patient)
                .doctor(doctor)
                .scheduledAt(request.getScheduledAt())
                .durationMins(request.getDurationMins())
                .reason(request.getReason())
                .status(AppointmentStatus.PENDING)
                .build();

        Appointment saved;
        try {
            saved = appointmentRepository.save(appointment);
        } catch (DataIntegrityViolationException ex) {
            // DB-level constraint violation (race condition: two requests beat the app-level check)
            throw new MediBookException("Doctor is not available at the requested time",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }

        // Publish Kafka event
        eventProducer.publishAppointmentEvent(buildEvent(saved, "BOOKED"));
        eventProducer.publishAuditEvent(buildAuditEvent(saved, patientId, "APPOINTMENT_BOOKED"));

        log.info("Appointment [{}] booked by patient [{}] with doctor [{}]",
                saved.getId(), patientId, request.getDoctorId());

        return AppointmentResponse.fromEntity(saved);
    }

    @CacheEvict(value = "appointments", key = "#id")
    @Transactional
    public AppointmentResponse confirm(Long id, UserPrincipal principal) {
        Appointment appt = getAndValidate(id);
        boolean isAdmin = principal.hasRole("ROLE_ADMIN");
        if (!isAdmin && !appt.getDoctor().getUser().getId().equals(principal.getId())) {
            throw new MediBookException("Only the assigned doctor can confirm this appointment",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        appt.setStatus(AppointmentStatus.CONFIRMED);
        Appointment saved = appointmentRepository.save(appt);
        eventProducer.publishAppointmentEvent(buildEvent(saved, "CONFIRMED"));
        return AppointmentResponse.fromEntity(saved);
    }

    @CacheEvict(value = "appointments", key = "#id")
    @Transactional
    public AppointmentResponse cancel(Long id, UserPrincipal principal) {
        Appointment appt = getAndValidate(id);
        if (appt.getStatus() == AppointmentStatus.COMPLETED) {
            throw new MediBookException("Cannot cancel a completed appointment",
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION");
        }
        boolean isAdmin = principal.hasRole("ROLE_ADMIN");
        boolean isPatient = appt.getPatient().getId().equals(principal.getId());
        boolean isAssignedDoctor = appt.getDoctor().getUser().getId().equals(principal.getId());
        if (!isAdmin && !isPatient && !isAssignedDoctor) {
            throw new MediBookException("Not authorized to cancel this appointment",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        appt.setStatus(AppointmentStatus.CANCELLED);
        Appointment saved = appointmentRepository.save(appt);
        eventProducer.publishAppointmentEvent(buildEvent(saved, "CANCELLED"));
        return AppointmentResponse.fromEntity(saved);
    }

    @Cacheable(value = "appointments", key = "#id")
    @Transactional(readOnly = true)
    public AppointmentResponse getById(Long id) {
        return appointmentRepository.findByIdWithDetails(id)
                .map(AppointmentResponse::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", id));
    }

    @Transactional(readOnly = true)
    public Page<AppointmentResponse> getByPatient(Long patientId, Pageable pageable) {
        return appointmentRepository.findByPatientId(patientId, pageable)
                .map(AppointmentResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<AppointmentResponse> getByDoctor(Long doctorId, Pageable pageable) {
        return appointmentRepository.findByDoctorId(doctorId, pageable)
                .map(AppointmentResponse::fromEntity);
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

    private AuditEvent buildAuditEvent(Appointment a, Long actorId, String action) {
        return AuditEvent.builder()
                .action(action)
                .actorId(actorId)
                .actorEmail(a.getPatient().getEmail())
                .resourceType("Appointment")
                .resourceId(String.valueOf(a.getId()))
                .detail("Appointment scheduled for " + a.getScheduledAt())
                .build();
    }
}
