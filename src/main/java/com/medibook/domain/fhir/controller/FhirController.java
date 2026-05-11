package com.medibook.domain.fhir.controller;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.fhir.service.FhirMapper;
import com.medibook.domain.patient.entity.PatientProfile;
import com.medibook.domain.patient.repository.PatientProfileRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * FHIR R4 export endpoints — read-only, publicly accessible for interoperability.
 * PHI in Observation/ConsultationNote is masked; full access requires SMART on FHIR OAuth2.
 * Production: add OAuth2 SMART on FHIR authorization via Spring Authorization Server.
 */
@RestController
@RequestMapping(value = "/api/v1/fhir", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "FHIR R4", description = "HL7 FHIR R4 interoperability endpoints (read-only)")
public class FhirController {

    private final FhirMapper                fhirMapper;
    private final UserRepository            userRepository;
    private final PatientProfileRepository  patientProfileRepository;
    private final DoctorRepository          doctorRepository;
    private final AppointmentRepository     appointmentRepository;
    private final ConsultationNoteRepository consultationNoteRepository;

    @GetMapping("/Patient/{id}")
    @Operation(summary = "Export patient as FHIR R4 Patient resource")
    public Map<String, Object> getPatient(@PathVariable Long id) {
        User user           = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        PatientProfile profile = patientProfileRepository.findByUserId(id).orElse(null);
        return fhirMapper.toFhirPatient(user, profile);
    }

    @GetMapping("/Practitioner/{id}")
    @Operation(summary = "Export doctor as FHIR R4 Practitioner resource")
    public Map<String, Object> getPractitioner(@PathVariable Long id) {
        Doctor doctor = doctorRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", id));
        return fhirMapper.toFhirPractitioner(doctor);
    }

    @GetMapping("/Appointment/{id}")
    @Operation(summary = "Export appointment as FHIR R4 Appointment resource")
    public Map<String, Object> getAppointment(@PathVariable Long id) {
        Appointment appointment = appointmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", id));
        return fhirMapper.toFhirAppointment(appointment);
    }

    @GetMapping("/Observation/consultation/{appointmentId}")
    @Operation(summary = "Export consultation note as FHIR R4 Observation (PHI masked for public access)")
    public Map<String, Object> getConsultationObservation(@PathVariable Long appointmentId) {
        ConsultationNote note = consultationNoteRepository.findByAppointmentId(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ConsultationNote", "appointmentId", appointmentId));
        return fhirMapper.toFhirObservation(note);
    }
}
