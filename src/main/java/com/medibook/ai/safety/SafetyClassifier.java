package com.medibook.ai.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rule-based safety classifier for inbound chat messages.
 *
 * Runs BEFORE any AI call. If a message is URGENT or BLOCKED, the AI pipeline
 * is short-circuited. No AI provider latency is incurred for escalations.
 *
 * Pattern sets are case-insensitive and ordered by severity (URGENT > CLINICAL > BLOCKED).
 *
 * IMPORTANT: This is a first-pass rule engine. It is NOT a substitute for
 * clinical triage by a licensed healthcare professional.
 */
@Slf4j
@Service
public class SafetyClassifier {

    // ── Emergency / Urgent Symptoms ──────────────────────────────────────────
    private static final List<Pattern> URGENT_PATTERNS = compile(
            "chest pain", "chest tightness", "chest pressure",
            "can't breathe", "cannot breathe", "shortness of breath", "difficulty breathing",
            "stroke", "face drooping", "arm weakness", "slurred speech", "sudden numbness",
            "severe bleeding", "blood everywhere", "losing lots of blood",
            "suicidal", "suicide", "kill myself", "end my life", "want to die",
            "overdose", "took too many pills", "unconscious", "not waking up",
            "severe pain", "unbearable pain", "excruciating pain",
            "allergic reaction", "anaphylaxis", "throat closing", "tongue swelling",
            "pregnancy emergency", "heavy bleeding pregnant", "water broke",
            "seizure", "convulsions", "won't stop shaking",
            "call 911", "call 999", "call ambulance", "emergency"
    );

    // ── Clinical Questions requiring doctor involvement ───────────────────────
    private static final List<Pattern> CLINICAL_PATTERNS = compile(
            "diagnos", "prescri", "medication", "drug", "dosage", "dose",
            "treatment", "cure", "surgery", "operation", "biopsy",
            "cancer", "tumor", "tumour", "malignant", "benign",
            "is it serious", "how serious", "what disease", "what condition",
            "should i take", "can i take", "stop taking", "increase dose",
            "test result", "lab result", "blood test", "scan result", "mri", "ct scan",
            "prognosis", "recovery time", "will i be okay"
    );

    // ── Prompt injection / jailbreak attempts ────────────────────────────────
    private static final List<Pattern> BLOCKED_PATTERNS = compile(
            "ignore previous instructions", "ignore all instructions",
            "system prompt", "act as", "pretend you are", "you are now",
            "jailbreak", "dan mode", "developer mode",
            "forget you are", "you are an ai", "you are not",
            "override safety", "bypass restrictions", "disable safety",
            "reveal your prompt", "show your instructions", "what is your system prompt"
    );

    /**
     * Classifies a message body and returns a SafetyClassification.
     * Never throws — returns SAFE on unexpected errors.
     */
    public SafetyClassification classify(String messageBody) {
        if (messageBody == null || messageBody.isBlank()) {
            return SafetyClassification.safe();
        }

        String lower = messageBody.toLowerCase();

        try {
            // 1. Check BLOCKED first — stops all AI processing
            List<String> blockedMatches = matchAll(BLOCKED_PATTERNS, lower);
            if (!blockedMatches.isEmpty()) {
                log.warn("SafetyClassifier BLOCKED — patterns matched: {}", blockedMatches);
                return SafetyClassification.builder()
                        .label(SafetyLabel.BLOCKED)
                        .matchedPatterns(blockedMatches)
                        .reason("Prompt injection or jailbreak attempt detected")
                        .requiresEscalation(false)
                        .build();
            }

            // 2. Check URGENT — triggers immediate escalation
            List<String> urgentMatches = matchAll(URGENT_PATTERNS, lower);
            if (!urgentMatches.isEmpty()) {
                log.warn("SafetyClassifier URGENT — patterns matched: {}", urgentMatches);
                return SafetyClassification.builder()
                        .label(SafetyLabel.URGENT)
                        .matchedPatterns(urgentMatches)
                        .reason("Emergency or high-risk symptom keywords detected")
                        .requiresEscalation(true)
                        .build();
            }

            // 3. Check CLINICAL — route to doctor draft, not direct AI reply
            List<String> clinicalMatches = matchAll(CLINICAL_PATTERNS, lower);
            if (!clinicalMatches.isEmpty()) {
                log.debug("SafetyClassifier CLINICAL_QUERY — patterns: {}", clinicalMatches);
                return SafetyClassification.builder()
                        .label(SafetyLabel.CLINICAL_QUERY)
                        .matchedPatterns(clinicalMatches)
                        .reason("Clinical question detected — requires doctor review")
                        .requiresEscalation(false)
                        .build();
            }

            return SafetyClassification.safe();

        } catch (Exception ex) {
            log.error("SafetyClassifier error — defaulting to SAFE: {}", ex.getMessage());
            return SafetyClassification.safe();
        }
    }

    private List<String> matchAll(List<Pattern> patterns, String text) {
        List<String> matches = new ArrayList<>();
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                matches.add(p.pattern());
            }
        }
        return matches;
    }

    private static List<Pattern> compile(String... keywords) {
        List<Pattern> patterns = new ArrayList<>();
        for (String kw : keywords) {
            patterns.add(Pattern.compile(Pattern.quote(kw), Pattern.CASE_INSENSITIVE));
        }
        return List.copyOf(patterns);
    }
}
