package com.medibook.domain.intelligence.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stub provider — active when no real NLP provider is configured.
 * Returns a structured placeholder with the mandatory disclaimer.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.intelligence.nlp-provider", havingValue = "stub", matchIfMissing = true)
public class StubClinicalNlpProvider implements ClinicalNlpPort {

    @Override
    public NlpProvider getProvider() { return NlpProvider.STUB; }

    @Override
    public TriageOutput triage(List<String> symptoms, String patientAge, String patientGender) {
        log.info("StubClinicalNlpProvider: triage called with {} symptoms", symptoms.size());
        return new TriageOutput(
                "AI-ASSISTED SUMMARY — NOT A DIAGNOSIS — DOCTOR REVIEW REQUIRED\n" +
                "Reported symptoms: " + String.join(", ", symptoms),
                List.of("[Awaiting integration with approved clinical NLP service]"),
                "UNKNOWN",
                true,
                "⚠ This is a stub output from the StubClinicalNlpProvider. " +
                "It contains no clinical information. Integrate an approved NLP provider before use. " +
                "This output IS NOT a diagnosis and MUST NOT be used for clinical decisions.",
                "stub-v0.1"
        );
    }
}
