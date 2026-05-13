package com.medibook.domain.intelligence.provider;

import java.util.List;

/**
 * Strategy interface for clinical NLP triage.
 * Implementations must NEVER produce output that could be interpreted as a final diagnosis.
 * All output must be marked as AI-assisted and require mandatory doctor review.
 */
public interface ClinicalNlpPort {

    NlpProvider getProvider();

    TriageOutput triage(List<String> symptoms, String patientAge, String patientGender);

    enum NlpProvider { STUB, CLAUDE_API, AWS_COMPREHEND_MEDICAL }

    record TriageOutput(
            String summary,
            List<String> possibleConsiderations,
            String urgencyIndicator,   // LOW | MEDIUM | HIGH | EMERGENCY
            boolean requiresDoctorReview,
            String disclaimer,
            String providerVersion
    ) {}
}
