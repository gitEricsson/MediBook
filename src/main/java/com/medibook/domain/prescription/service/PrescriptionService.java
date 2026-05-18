package com.medibook.domain.prescription.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.prescription.dto.PrescriptionDtos.*;
import com.medibook.domain.prescription.entity.Prescription;
import com.medibook.domain.prescription.entity.PrescriptionStatus;
import com.medibook.domain.prescription.repository.PrescriptionRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Structured prescription management. One appointment can issue many prescription
 * lines. The doctor on the appointment (or admin) is the only writer; patients and
 * the doctor/admin can read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrescriptionService {

    private final PrescriptionRepository repository;
    private final AppointmentRepository appointmentRepository;

    @Transactional
    public Response create(CreateRequest req, UserPrincipal principal) {
        Appointment appointment = appointmentRepository.findByIdWithDetails(req.appointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", req.appointmentId()));

        ensureDoctorOnAppointment(appointment, principal);

        LocalDateTime now      = LocalDateTime.now();
        LocalDateTime expires  = req.durationDays() != null && req.durationDays() > 0
                ? now.plusDays(req.durationDays())
                : null;

        Prescription rx = Prescription.builder()
                .appointment(appointment)
                .doctor(appointment.getDoctor())
                .patient(appointment.getPatient())
                .drugName(req.drugName().trim())
                .dosage(req.dosage().trim())
                .route(req.route())
                .frequency(req.frequency().trim())
                .durationDays(req.durationDays())
                .instructions(req.instructions())
                .status(PrescriptionStatus.ACTIVE)
                .issuedAt(now)
                .expiresAt(expires)
                .build();

        return Response.from(repository.save(rx));
    }

    @Transactional
    public Response update(Long id, UpdateRequest req, UserPrincipal principal) {
        Prescription rx = loadOrThrow(id);
        ensureDoctorOnAppointment(rx.getAppointment(), principal);
        if (rx.getStatus() != PrescriptionStatus.ACTIVE) {
            throw new MediBookException("Only active prescriptions can be edited",
                    HttpStatus.CONFLICT, "PRESCRIPTION_NOT_ACTIVE");
        }
        if (req.dosage() != null && !req.dosage().isBlank())     rx.setDosage(req.dosage().trim());
        if (req.route() != null)                                 rx.setRoute(req.route());
        if (req.frequency() != null && !req.frequency().isBlank()) rx.setFrequency(req.frequency().trim());
        if (req.durationDays() != null)                          rx.setDurationDays(req.durationDays());
        if (req.instructions() != null)                          rx.setInstructions(req.instructions());
        return Response.from(repository.save(rx));
    }

    @Transactional
    public Response cancel(Long id, CancelRequest req, UserPrincipal principal) {
        Prescription rx = loadOrThrow(id);
        ensureDoctorOnAppointment(rx.getAppointment(), principal);
        if (rx.getStatus() == PrescriptionStatus.CANCELLED) {
            throw new MediBookException("Prescription already cancelled",
                    HttpStatus.CONFLICT, "ALREADY_CANCELLED");
        }
        rx.setStatus(PrescriptionStatus.CANCELLED);
        rx.setCancelledAt(LocalDateTime.now());
        rx.setCancelledReason(req != null ? req.reason() : null);
        return Response.from(repository.save(rx));
    }

    @Transactional(readOnly = true)
    public List<Response> listForAppointment(Long appointmentId, UserPrincipal principal) {
        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", appointmentId));
        ensureCanReadAppointment(appointment, principal);
        return repository.findByAppointmentIdOrderByIssuedAtDesc(appointmentId)
                .stream().map(Response::from).toList();
    }

    @Transactional(readOnly = true)
    public Page<Response> listForPatient(Long patientId, PrescriptionStatus status, Pageable pageable, UserPrincipal principal) {
        // A patient can only see their own list; doctors/admins can pull anyone.
        if (!principal.hasRole("ROLE_ADMIN") && !principal.hasRole("ROLE_DOCTOR")
                && !principal.getId().equals(patientId)) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        Page<Prescription> page = status == null
                ? repository.findByPatientId(patientId, pageable)
                : repository.findByPatientIdAndStatus(patientId, status, pageable);
        return page.map(Response::from);
    }

    @Transactional(readOnly = true)
    public Response getById(Long id, UserPrincipal principal) {
        Prescription rx = loadOrThrow(id);
        ensureCanReadAppointment(rx.getAppointment(), principal);
        return Response.from(rx);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Prescription loadOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription", "id", id));
    }

    private void ensureDoctorOnAppointment(Appointment appt, UserPrincipal principal) {
        if (principal.hasRole("ROLE_ADMIN")) return;
        Long doctorUserId = appt.getDoctor() != null && appt.getDoctor().getUser() != null
                ? appt.getDoctor().getUser().getId() : null;
        if (doctorUserId == null || !doctorUserId.equals(principal.getId())) {
            throw new MediBookException(
                    "Only the doctor on this appointment can write prescriptions",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }

    private void ensureCanReadAppointment(Appointment appt, UserPrincipal principal) {
        if (principal.hasRole("ROLE_ADMIN")) return;
        Long doctorUserId = appt.getDoctor() != null && appt.getDoctor().getUser() != null
                ? appt.getDoctor().getUser().getId() : null;
        Long patientId    = appt.getPatient() != null ? appt.getPatient().getId() : null;
        boolean ok = (doctorUserId != null && doctorUserId.equals(principal.getId()))
                || (patientId != null && patientId.equals(principal.getId()));
        if (!ok) {
            throw new MediBookException("Not authorized for this appointment",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }
}
