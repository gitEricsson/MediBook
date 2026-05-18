package com.medibook.ai.support.classifier;

import com.medibook.ai.safety.SafetyClassification;
import com.medibook.ai.safety.SafetyClassifier;
import com.medibook.ai.safety.SafetyLabel;
import com.medibook.ai.support.dto.SupportMessageClassification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Two-stage message classifier for the support chat widget.
 *
 * Stage 1 — Safety gate (delegates to existing SafetyClassifier):
 *   URGENT         → MEDICAL_EMERGENCY (short-circuits; no AI call)
 *   CLINICAL_QUERY → MEDICAL_SYMPTOM   (short-circuits; no AI call)
 *   BLOCKED        → ABUSE_OR_SPAM     (short-circuits; no AI call)
 *
 * Stage 2 — Support-specific intent classification (for SAFE messages):
 *   Applies lightweight keyword patterns to route to the correct response tone.
 *
 * Never throws. Returns UNKNOWN on unexpected errors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportMessageClassifier {

    private final SafetyClassifier safetyClassifier;

    private static final List<Pattern> BOOKING_PATTERNS = compile(
            "book", "schedule", "reschedule", "cancel",
            "slot", "available", "availability", "reserve"
    );

    // Checked BEFORE booking to avoid false positives on "my appointment fee"
    private static final List<Pattern> PAYMENT_PATTERNS = compile(
            "pay", "payment", "invoice", "billing", "bill", "cost",
            "price", "fee", "charge", "refund", "receipt", "transaction"
    );

    private static final List<Pattern> NAVIGATION_PATTERNS = compile(
            "how do i", "how to", "where is", "find", "navigate",
            "dashboard", "profile", "settings", "menu", "button",
            "login", "sign in", "sign up", "register", "account"
    );

    private static final List<Pattern> HEALTH_EDUCATION_PATTERNS = compile(
            "what is", "what are", "explain", "tell me about",
            "how does", "health tip", "healthy", "wellness",
            "diet", "exercise", "nutrition", "sleep", "stress",
            "vaccine", "vaccination", "screening", "check-up"
    );

    private static final List<Pattern> PHI_PATTERNS = compile(
            "my ssn", "social security", "my passport", "date of birth is",
            "my blood type is", "my diagnosis is", "my prescription is",
            "my insurance number", "my medical record"
    );

    public ClassificationResult classify(String message) {
        if (message == null || message.isBlank()) {
            return new ClassificationResult(SupportMessageClassification.SUPPORT,
                    SafetyClassification.safe(), List.of());
        }

        String lower = message.toLowerCase();
        // Checked before SafetyClassifier so PHI statements that look clinical (e.g. "my diagnosis is X")
        // are caught here and not forwarded to any AI provider.
        List<String> phiMatches = matchAll(PHI_PATTERNS, lower);
        if (!phiMatches.isEmpty()) {
            return new ClassificationResult(SupportMessageClassification.PHI_DETECTED,
                    SafetyClassification.safe(), phiMatches);
        }
        SafetyClassification safety = safetyClassifier.classify(message);

        if (safety.getLabel() == SafetyLabel.BLOCKED) {
            return new ClassificationResult(SupportMessageClassification.ABUSE_OR_SPAM,
                    safety, safety.getMatchedPatterns());
        }
        if (safety.getLabel() == SafetyLabel.URGENT) {
            return new ClassificationResult(SupportMessageClassification.MEDICAL_EMERGENCY,
                    safety, safety.getMatchedPatterns());
        }
        if (safety.getLabel() == SafetyLabel.CLINICAL_QUERY) {
            return new ClassificationResult(SupportMessageClassification.MEDICAL_SYMPTOM,
                    safety, safety.getMatchedPatterns());
        }
        // when "appointment" appears in payment-related messages like "refund for my appointment")
        try {
            if (!matchAll(PAYMENT_PATTERNS, lower).isEmpty()) {
                return new ClassificationResult(SupportMessageClassification.PAYMENT_HELP,
                        safety, matchAll(PAYMENT_PATTERNS, lower));
            }
            if (!matchAll(BOOKING_PATTERNS, lower).isEmpty()) {
                return new ClassificationResult(SupportMessageClassification.BOOKING_HELP,
                        safety, matchAll(BOOKING_PATTERNS, lower));
            }
            if (!matchAll(NAVIGATION_PATTERNS, lower).isEmpty()) {
                return new ClassificationResult(SupportMessageClassification.APP_NAVIGATION,
                        safety, matchAll(NAVIGATION_PATTERNS, lower));
            }
            if (!matchAll(HEALTH_EDUCATION_PATTERNS, lower).isEmpty()) {
                return new ClassificationResult(SupportMessageClassification.GENERAL_HEALTH_EDUCATION,
                        safety, matchAll(HEALTH_EDUCATION_PATTERNS, lower));
            }
            return new ClassificationResult(SupportMessageClassification.SUPPORT, safety, List.of());

        } catch (Exception ex) {
            log.error("SupportMessageClassifier stage-2 error — defaulting to UNKNOWN: {}", ex.getMessage());
            return new ClassificationResult(SupportMessageClassification.UNKNOWN, safety, List.of());
        }
    }

    private List<String> matchAll(List<Pattern> patterns, String text) {
        List<String> hits = new ArrayList<>();
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) hits.add(p.pattern());
        }
        return hits;
    }

    private static List<Pattern> compile(String... keywords) {
        List<Pattern> list = new ArrayList<>();
        for (String kw : keywords) {
            list.add(Pattern.compile(Pattern.quote(kw), Pattern.CASE_INSENSITIVE));
        }
        return List.copyOf(list);
    }

    public record ClassificationResult(
            SupportMessageClassification classification,
            SafetyClassification safety,
            List<String> matchedPatterns
    ) {}
}
