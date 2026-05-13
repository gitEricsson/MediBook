package com.medibook.domain.intelligence.service;

import com.medibook.domain.intelligence.provider.ClinicalNlpPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Symptom triage service — delegates to the configured ClinicalNlpPort.
 *
 * CRITICAL SAFETY CONTRACT (must never be relaxed):
 * - Output is NEVER a final diagnosis.
 * - requiresDoctorReview is always true.
 * - Doctor MUST review before any clinical note is saved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SymptomTriageService {

    private final ClinicalNlpPort clinicalNlpPort;

    public TriageResult triageSymptoms(Long patientId, List<String> symptoms) {
        return triageSymptoms(patientId, symptoms, null, null);
    }

    public TriageResult triageSymptoms(Long patientId, List<String> symptoms,
                                       String patientAge, String patientGender) {
        if (symptoms == null || symptoms.isEmpty()) return TriageResult.empty();

        log.info("Symptom triage for patient [{}] with {} symptoms via [{}]",
                patientId, symptoms.size(), clinicalNlpPort.getProvider());

        ClinicalNlpPort.TriageOutput output = clinicalNlpPort.triage(symptoms, patientAge, patientGender);

        return new TriageResult(
                output.summary(),
                output.possibleConsiderations(),
                output.urgencyIndicator(),
                "REQUIRES_DOCTOR_REVIEW",
                true,
                output.disclaimer(),
                clinicalNlpPort.getProvider().name()
        );
    }

    public record TriageResult(
            String summary,
            List<String> possibleConsiderations,
            String urgencyIndicator,
            String status,
            boolean requiresDoctorReview,
            String disclaimer,
            String provider
    ) {
        static TriageResult empty() {
            return new TriageResult("No symptoms provided.", List.of(),
                    "UNKNOWN", "NO_INPUT", true, "Provide symptoms for triage.", "NONE");
        }
    }
}
