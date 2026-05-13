package com.medibook.domain.consultation.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.consultation.dto.ConsultationNoteRequest;
import com.medibook.domain.consultation.dto.ConsultationNoteResponse;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsultationNoteService {

    private final ConsultationNoteRepository noteRepository;
    private final AppointmentRepository appointmentRepository;

    @Transactional
    public ConsultationNoteResponse create(Long appointmentId, ConsultationNoteRequest request) {
        if (noteRepository.findByAppointmentId(appointmentId).isPresent()) {
            throw new MediBookException("Consultation note already exists for this appointment",
                    HttpStatus.CONFLICT, "NOTE_EXISTS");
        }

        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", appointmentId));

        ConsultationNote note = ConsultationNote.builder()
                .appointment(appointment)
                .doctor(appointment.getDoctor())
                .diagnosis(request.getDiagnosis())           // encrypted by PhiAttributeConverter on save
                .treatmentPlan(request.getTreatmentPlan())   // encrypted by PhiAttributeConverter on save
                .prescriptions(request.getPrescriptions())
                .followUpDate(request.getFollowUpDate())
                .build();

        return ConsultationNoteResponse.fromEntity(noteRepository.save(note));
    }

    @Transactional
    public ConsultationNoteResponse create(Long appointmentId, ConsultationNoteRequest request, UserPrincipal principal) {
        if (noteRepository.findByAppointmentId(appointmentId).isPresent()) {
            throw new MediBookException("Consultation note already exists for this appointment",
                    HttpStatus.CONFLICT, "NOTE_EXISTS");
        }

        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", appointmentId));
        ensureCanAccessAppointment(appointment, principal);

        ConsultationNote note = ConsultationNote.builder()
                .appointment(appointment)
                .doctor(appointment.getDoctor())
                .diagnosis(request.getDiagnosis())
                .treatmentPlan(request.getTreatmentPlan())
                .prescriptions(request.getPrescriptions())
                .followUpDate(request.getFollowUpDate())
                .build();

        return ConsultationNoteResponse.fromEntity(noteRepository.save(note));
    }

    @Transactional(readOnly = true)
    public ConsultationNoteResponse getByAppointment(Long appointmentId) {
        return noteRepository.findByAppointmentId(appointmentId)
                .map(ConsultationNoteResponse::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("ConsultationNote", "appointmentId", appointmentId));
    }

    @Transactional(readOnly = true)
    public ConsultationNoteResponse getByAppointment(Long appointmentId, UserPrincipal principal) {
        ConsultationNote note = noteRepository.findByAppointmentId(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ConsultationNote", "appointmentId", appointmentId));
        ensureCanAccessAppointment(note.getAppointment(), principal);
        return ConsultationNoteResponse.fromEntity(note);
    }

    @Transactional(readOnly = true)
    public List<ConsultationNoteResponse> getPatientHistory(Long patientId) {
        return noteRepository.findByPatientId(patientId).stream()
                .map(ConsultationNoteResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConsultationNoteResponse> getPatientHistory(Long patientId, UserPrincipal principal) {
        // Only allow if the caller is the patient themselves or an admin
        if (!patientId.equals(principal.getId()) && !principal.hasRole("ROLE_ADMIN") && !principal.hasRole("ROLE_SUPER_ADMIN")) {
            throw new MediBookException("Cannot access another patient's consultation history",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return noteRepository.findByPatientId(patientId).stream()
                .map(ConsultationNoteResponse::fromEntity)
                .toList();
    }

    @Transactional
    public ConsultationNoteResponse update(Long id, ConsultationNoteRequest request) {
        ConsultationNote note = noteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConsultationNote", "id", id));
        note.setDiagnosis(request.getDiagnosis());
        note.setTreatmentPlan(request.getTreatmentPlan());
        note.setPrescriptions(request.getPrescriptions());
        note.setFollowUpDate(request.getFollowUpDate());
        return ConsultationNoteResponse.fromEntity(noteRepository.save(note));
    }

    @Transactional
    public ConsultationNoteResponse update(Long id, ConsultationNoteRequest request, UserPrincipal principal) {
        ConsultationNote note = noteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConsultationNote", "id", id));
        ensureCanAccessAppointment(note.getAppointment(), principal);
        note.setDiagnosis(request.getDiagnosis());
        note.setTreatmentPlan(request.getTreatmentPlan());
        note.setPrescriptions(request.getPrescriptions());
        note.setFollowUpDate(request.getFollowUpDate());
        return ConsultationNoteResponse.fromEntity(noteRepository.save(note));
    }

    private void ensureCanAccessAppointment(Appointment appointment, UserPrincipal principal) {
        if (principal.hasRole("ROLE_ADMIN")) {
            return;
        }
        if (!appointment.getDoctor().getUser().getId().equals(principal.getId())) {
            throw new MediBookException("Not authorized to access this consultation note",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }
}
