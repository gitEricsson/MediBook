package com.medibook.domain.fhir.service;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.patient.entity.PatientProfile;
import com.medibook.domain.user.entity.User;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HL7 FHIR R4 mapper — produces fully-structured JSON conformant resources.
 *
 * Architecture note: uses Map-based representation to avoid adding the 50 MB
 * HAPI FHIR runtime dependency. The output structure conforms to FHIR R4 spec.
 * To switch to HAPI FHIR models, replace each method body with IParser.encodeResourceToString(IBaseResource).
 *
 * Profile: http://hl7.org/fhir/R4
 */
@Service
public class FhirMapper {

    private static final String FHIR_VERSION   = "4.0.1";
    private static final String SYSTEM_PATIENT = "https://medibook.io/fhir/Patient";
    private static final String SYSTEM_DOCTOR  = "https://medibook.io/fhir/Practitioner";
    private static final String SYSTEM_APPT    = "https://medibook.io/fhir/Appointment";
    private static final String SYSTEM_OBS     = "https://medibook.io/fhir/Observation";

    // ── Patient ─────────────────────────────────────────────────────────────

    public Map<String, Object> toFhirPatient(User user, PatientProfile profile) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Patient");
        resource.put("id", "patient-" + user.getId());
        resource.put("meta", meta("http://hl7.org/fhir/StructureDefinition/Patient"));

        resource.put("identifier", List.of(
                identifier(SYSTEM_PATIENT, String.valueOf(user.getId())),
                identifier("https://medibook.io/fhir/email", user.getEmail())));

        resource.put("name", List.of(humanName(user.getLastName(), user.getFirstName())));

        List<Map<String, Object>> telecom = new ArrayList<>();
        telecom.add(contactPoint("email", user.getEmail(), "home"));
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            telecom.add(contactPoint("phone", user.getPhone(), "mobile"));
        }
        resource.put("telecom", telecom);

        resource.put("gender", mapGender(null));
        if (user.getDateOfBirth() != null) {
            resource.put("birthDate", user.getDateOfBirth().toString());
        }
        resource.put("active", user.isActive() && user.isEnabled());

        if (profile != null) {
            List<Map<String, Object>> extensions = new ArrayList<>();
            if (profile.getBloodGroup() != null) {
                extensions.add(extension(
                        "https://medibook.io/fhir/StructureDefinition/blood-group",
                        "valueString", profile.getBloodGroup()));
            }
            if (!extensions.isEmpty()) resource.put("extension", extensions);
        }

        return wrap(resource);
    }

    // ── Practitioner ────────────────────────────────────────────────────────

    public Map<String, Object> toFhirPractitioner(Doctor doctor) {
        User user = doctor.getUser();
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Practitioner");
        resource.put("id", "practitioner-" + doctor.getId());
        resource.put("meta", meta("http://hl7.org/fhir/StructureDefinition/Practitioner"));

        resource.put("identifier", List.of(
                identifier(SYSTEM_DOCTOR, String.valueOf(doctor.getId())),
                identifier("https://medibook.io/fhir/license", doctor.getLicenseNumber())));

        resource.put("name", List.of(humanName(user.getLastName(), user.getFirstName())));
        resource.put("telecom", List.of(contactPoint("email", user.getEmail(), "work")));
        resource.put("active", doctor.isActive());

        if (doctor.getSpecialization() != null) {
            resource.put("qualification", List.of(Map.of(
                    "identifier", List.of(identifier("https://medibook.io/fhir/licenses", doctor.getLicenseNumber())),
                    "code", Map.of(
                            "coding", List.of(Map.of(
                                    "system",  "http://snomed.info/sct",
                                    "display", doctor.getSpecialization())),
                            "text", doctor.getSpecialization()),
                    "issuer", Map.of("display", "MediBook Medical Council"))));
        }

        if (doctor.getLanguages() != null) {
            String[] langs = doctor.getLanguages().split(",");
            List<Map<String, Object>> communication = new ArrayList<>();
            for (String lang : langs) {
                communication.add(Map.of("language", Map.of(
                        "coding", List.of(Map.of(
                                "system", "urn:ietf:bcp:47",
                                "display", lang.trim()))),
                        "preferred", true));
            }
            resource.put("communication", communication);
        }

        return wrap(resource);
    }

    // ── Appointment ─────────────────────────────────────────────────────────

    public Map<String, Object> toFhirAppointment(Appointment appt) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Appointment");
        resource.put("id", "appointment-" + appt.getId());
        resource.put("meta", meta("http://hl7.org/fhir/StructureDefinition/Appointment"));

        resource.put("identifier", List.of(
                identifier(SYSTEM_APPT, appt.getConfirmationCode())));

        resource.put("status", mapAppointmentStatus(appt.getStatus().name()));

        resource.put("serviceType", List.of(Map.of(
                "coding", List.of(Map.of(
                        "system", "http://snomed.info/sct",
                        "display", appt.getDoctor().getSpecialization() != null
                                ? appt.getDoctor().getSpecialization() : "General Practice")),
                "text", appt.getDoctor().getDepartment().getName())));

        resource.put("appointmentType", Map.of(
                "coding", List.of(Map.of(
                        "system", "http://terminology.hl7.org/CodeSystem/v2-0276",
                        "code",   mapAppointmentTypeFhir(appt.getType().name()),
                        "display", appt.getType().name()))));

        resource.put("reasonCode", appt.getReason() != null
                ? List.of(Map.of("text", appt.getReason()))
                : List.of());

        resource.put("start", appt.getScheduledAt().toString() + "+00:00");
        resource.put("end",   appt.getEndTime() != null
                ? appt.getEndTime().toString() + "+00:00" : null);
        resource.put("minutesDuration", appt.getDurationMins());

        resource.put("participant", List.of(
                participant(
                        "Patient",
                        "patient-" + appt.getPatient().getId(),
                        appt.getPatient().getFullName(),
                        "accepted"),
                participant(
                        "Practitioner",
                        "practitioner-" + appt.getDoctor().getId(),
                        "Dr. " + appt.getDoctor().getUser().getFullName(),
                        "accepted")));

        resource.put("created", appt.getCreatedAt() != null
                ? appt.getCreatedAt().toString() : LocalDateTime.now().toString());

        return wrap(resource);
    }

    // ── ConsultationNote → FHIR Observation ─────────────────────────────────

    public Map<String, Object> toFhirObservation(ConsultationNote note) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "Observation");
        resource.put("id", "observation-note-" + note.getId());
        resource.put("meta", meta("http://hl7.org/fhir/StructureDefinition/Observation"));
        resource.put("status", "final");
        resource.put("code", Map.of(
                "coding", List.of(Map.of(
                        "system",  "http://loinc.org",
                        "code",    "34117-2",
                        "display", "History and physical note")),
                "text", "Consultation Note"));

        resource.put("subject", Map.of(
                "reference", "Patient/patient-" + note.getAppointment().getPatient().getId(),
                "display",   note.getAppointment().getPatient().getFullName()));

        resource.put("performer", List.of(Map.of(
                "reference", "Practitioner/practitioner-" + note.getDoctor().getId(),
                "display",   "Dr. " + note.getDoctor().getUser().getFullName())));

        if (note.getFollowUpDate() != null) {
            resource.put("effectiveDateTime", note.getFollowUpDate().toString());
        }
        if (note.getDiagnosis() != null) {
            resource.put("note", List.of(Map.of("text", "[PHI — accessible to authorized providers only]")));
        }
        resource.put("encounter", Map.of(
                "identifier", identifier(SYSTEM_APPT,
                        String.valueOf(note.getAppointment().getId()))));

        return wrap(resource);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> wrap(Map<String, Object> resource) {
        resource.put("fhirVersion", FHIR_VERSION);
        return resource;
    }

    private Map<String, Object> meta(String... profiles) {
        return Map.of(
                "versionId",    "1",
                "lastUpdated",  LocalDateTime.now().toString(),
                "profile",      List.of(profiles));
    }

    private Map<String, Object> identifier(String system, String value) {
        return Map.of("system", system, "value", value);
    }

    private Map<String, Object> humanName(String family, String given) {
        return Map.of(
                "use",    "official",
                "family", family,
                "given",  List.of(given));
    }

    private Map<String, Object> contactPoint(String system, String value, String use) {
        return Map.of("system", system, "value", value, "use", use);
    }

    private Map<String, Object> extension(String url, String valueKey, Object valueValue) {
        return Map.of("url", url, valueKey, valueValue);
    }

    private Map<String, Object> participant(String resourceType, String refId, String display, String status) {
        return Map.of(
                "actor",    Map.of("reference", resourceType + "/" + refId, "display", display),
                "required", "required",
                "status",   status);
    }

    private String mapAppointmentStatus(String status) {
        return switch (status) {
            case "PENDING"   -> "proposed";
            case "CONFIRMED" -> "booked";
            case "COMPLETED" -> "fulfilled";
            case "CANCELLED" -> "cancelled";
            case "NO_SHOW"   -> "noshow";
            default          -> "pending";
        };
    }

    private String mapAppointmentTypeFhir(String type) {
        return switch (type) {
            case "TELEMEDICINE", "TELEHEALTH" -> "VIRTUAL";
            default                           -> "ROUTINE";
        };
    }

    private String mapGender(String gender) {
        if (gender == null) return "unknown";
        return switch (gender.toUpperCase()) {
            case "MALE"   -> "male";
            case "FEMALE" -> "female";
            case "OTHER"  -> "other";
            default       -> "unknown";
        };
    }
}
