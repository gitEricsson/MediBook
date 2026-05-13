package com.medibook.domain.intelligence.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AWS Comprehend Medical NLP provider.
 *
 * Production integration: add aws-java-sdk-comprehendmedical dependency and
 * use AWSComprehendMedical client to call DetectEntitiesV2 and InferICD10CM.
 *
 * Set AWS_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY in environment.
 * IAM permission required: comprehendmedical:DetectEntitiesV2
 *
 * CRITICAL: Same safety constraints as Claude provider — output is NOT a diagnosis.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.intelligence.nlp-provider", havingValue = "aws-comprehend", matchIfMissing = false)
public class AwsComprehendMedicalProvider implements ClinicalNlpPort {

    @Override
    public NlpProvider getProvider() { return NlpProvider.AWS_COMPREHEND_MEDICAL; }

    @Override
    public TriageOutput triage(List<String> symptoms, String patientAge, String patientGender) {
        log.info("AWS Comprehend Medical: analyzing {} symptoms", symptoms.size());

        // Production: construct AWSComprehendMedicalClient and call:
        //   DetectEntitiesV2Request -> extract MEDICAL_CONDITION, SIGN_OR_SYMPTOM
        //   InferICD10CMRequest     -> extract ICD-10 codes (doctor reviews for accuracy)
        //   InferRxNormRequest      -> flag potential medication interactions

        return new TriageOutput(
                "⚠ AI-ASSISTED SUMMARY — NOT A DIAGNOSIS — REQUIRES DOCTOR REVIEW\n\n" +
                "AWS Comprehend Medical analysis for: " + String.join(", ", symptoms),
                List.of("[AWS Comprehend Medical integration pending — add aws-java-sdk-comprehendmedical dependency]"),
                "UNKNOWN",
                true,
                "AWS Comprehend Medical output is NOT a medical diagnosis. Doctor review is mandatory.",
                "aws-comprehend-medical-v2"
        );
    }
}
