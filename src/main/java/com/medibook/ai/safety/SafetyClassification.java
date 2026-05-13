package com.medibook.ai.safety;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Immutable result of the SafetyClassifier for a single message.
 */
@Value
@Builder
public class SafetyClassification {

    SafetyLabel label;

    /** Keywords or phrases that triggered the classification */
    List<String> matchedPatterns;

    /** Human-readable reason — for audit log only, never shown to users */
    String reason;

    /** True if this classification should be escalated to doctor/admin immediately */
    boolean requiresEscalation;

    public static SafetyClassification safe() {
        return SafetyClassification.builder()
                .label(SafetyLabel.SAFE)
                .matchedPatterns(List.of())
                .reason("No safety concerns detected")
                .requiresEscalation(false)
                .build();
    }
}
