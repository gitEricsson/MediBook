package com.medibook.ai.safety;

/**
 * Safety classification labels assigned by SafetyClassifier to every inbound message.
 *
 * Processing rules:
 *  SAFE           → proceed normally, AI may respond
 *  CLINICAL_QUERY → route to doctor draft; AI may NOT respond directly to patient
 *  URGENT         → send emergency escalation message immediately; emit Kafka MEDICAL_URGENCY_FLAGGED
 *  BLOCKED        → do not generate AI response; log and stop silently
 */
public enum SafetyLabel {

    /** General conversation, admin questions, appointment prep */
    SAFE,

    /** Clinical question that needs doctor involvement — AI draft for doctor review only */
    CLINICAL_QUERY,

    /** Emergency/red-flag symptoms detected — escalate immediately */
    URGENT,

    /** Prompt injection, jailbreak attempt, or prohibited content — block silently */
    BLOCKED
}
