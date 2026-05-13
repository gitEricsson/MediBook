package com.medibook.domain.intelligence.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Claude API (Anthropic) provider for AI-assisted clinical NLP.
 *
 * CRITICAL SAFETY CONSTRAINTS (must not be removed):
 * - System prompt explicitly instructs the model to NOT provide a diagnosis
 * - Requires doctor review flag is always set to true
 * - Disclaimer is always appended
 * - Model output is structured to clearly distinguish from clinical notes
 *
 * Set CLAUDE_API_KEY in environment for production use.
 * Docs: https://docs.anthropic.com/claude/reference/messages_post
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.intelligence.nlp-provider", havingValue = "claude-api", matchIfMissing = false)
public class ClaudeApiClinicalNlpProvider implements ClinicalNlpPort {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";

    private static final String SYSTEM_PROMPT = """
            You are an AI assistant helping medical staff document patient-reported symptoms.

            STRICT RULES — NEVER VIOLATE:
            1. You MUST NOT provide a diagnosis.
            2. You MUST NOT prescribe medications or treatments.
            3. You MUST NOT make definitive medical statements.
            4. Your output is ONLY a structured summary for a qualified doctor to review.
            5. Always prefix your response with: "⚠ AI-ASSISTED SUMMARY — NOT A DIAGNOSIS — REQUIRES DOCTOR REVIEW"

            When given a list of symptoms, provide:
            - A brief, neutral description of the reported symptoms
            - A list of general medical considerations (NOT diagnoses) a doctor may want to evaluate
            - An urgency indicator (LOW/MEDIUM/HIGH/EMERGENCY) based purely on symptom severity keywords

            Format response as JSON: { "summary": "...", "considerations": [...], "urgency": "..." }
            """;

    @Value("${app.intelligence.claude-api.key:#{null}}")
    private String apiKey;

    @Value("${app.intelligence.claude-api.max-tokens:512}")
    private int maxTokens;

    private final ObjectMapper objectMapper;

    public ClaudeApiClinicalNlpProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public NlpProvider getProvider() { return NlpProvider.CLAUDE_API; }

    @Override
    @CircuitBreaker(name = "clinicalNlpProvider", fallbackMethod = "triageFallback")
    public TriageOutput triage(List<String> symptoms, String patientAge, String patientGender) {
        if (!isConfigured()) {
            log.warn("Claude API key not set — returning structured placeholder");
            return placeholder(symptoms);
        }

        try {
            String userMessage = buildUserMessage(symptoms, patientAge, patientGender);

            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model",      MODEL,
                    "max_tokens", maxTokens,
                    "system",     SYSTEM_PROMPT,
                    "messages",   List.of(Map.of("role", "user", "content", userMessage))
            ));

            String response = RestClient.builder()
                    .baseUrl(API_URL)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "")   // Anthropic uses x-api-key header
                    .defaultHeader("x-api-key", apiKey)
                    .defaultHeader("anthropic-version", "2023-06-01")
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build()
                    .post()
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root      = objectMapper.readTree(response);
            String content     = root.path("content").get(0).path("text").asText("");
            JsonNode parsed    = objectMapper.readTree(content);

            String summary     = parsed.path("summary").asText("AI summary unavailable.");
            List<String> considerations = new java.util.ArrayList<>();
            parsed.path("considerations").forEach(n -> considerations.add(n.asText()));
            String urgency     = parsed.path("urgency").asText("UNKNOWN");

            log.info("Claude API triage completed for {} symptoms, urgency={}", symptoms.size(), urgency);

            return new TriageOutput(
                    "⚠ AI-ASSISTED SUMMARY — NOT A DIAGNOSIS — REQUIRES DOCTOR REVIEW\n\n" + summary,
                    considerations,
                    urgency,
                    true,
                    "This output was generated by Claude AI. It is NOT a medical diagnosis. " +
                    "The treating physician MUST review and modify before any clinical note is saved.",
                    MODEL
            );

        } catch (Exception ex) {
            log.error("Claude API triage failed: {}", ex.getMessage());
            return placeholder(symptoms);
        }
    }

    TriageOutput triageFallback(List<String> symptoms, String age, String gender, Exception ex) {
        log.warn("ClinicalNlpProvider circuit open — returning placeholder");
        return placeholder(symptoms);
    }

    private String buildUserMessage(List<String> symptoms, String patientAge, String patientGender) {
        return String.format(
                "Patient: age=%s, gender=%s\nReported symptoms: %s\n\nProvide structured JSON output as specified.",
                patientAge != null ? patientAge : "unknown",
                patientGender != null ? patientGender : "unknown",
                String.join(", ", symptoms));
    }

    private TriageOutput placeholder(List<String> symptoms) {
        return new TriageOutput(
                "⚠ AI-ASSISTED SUMMARY — NOT A DIAGNOSIS — REQUIRES DOCTOR REVIEW\n\nSymptoms: " + String.join(", ", symptoms),
                List.of("Clinical assessment required by qualified physician"),
                "UNKNOWN",
                true,
                "AI provider is unavailable. A doctor must assess the patient directly.",
                "fallback-v1"
        );
    }

    private boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
}
