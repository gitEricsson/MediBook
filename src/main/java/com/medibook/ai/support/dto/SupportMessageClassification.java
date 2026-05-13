package com.medibook.ai.support.dto;

/**
 * Fine-grained classification of a support message.
 *
 * Processing rules:
 *  MEDICAL_EMERGENCY  → return emergency language; advise contacting emergency services
 *  MEDICAL_SYMPTOM    → refuse diagnosis; recommend booking a doctor
 *  PHI_DETECTED       → safe refusal; avoid sending sensitive data to any provider
 *  ABUSE_OR_SPAM      → safe refusal; no AI call made
 *  All others         → pass to AI support provider
 */
public enum SupportMessageClassification {

    /** General how-to, app help, account questions */
    SUPPORT,

    /** Where to find features, navigating the app */
    APP_NAVIGATION,

    /** Booking, cancelling, rescheduling appointments */
    BOOKING_HELP,

    /** Payments, invoices, pricing, refunds */
    PAYMENT_HELP,

    /** Non-diagnostic health info — always with disclaimers */
    GENERAL_HEALTH_EDUCATION,

    /** Clinical symptom question — redirected to doctor booking */
    MEDICAL_SYMPTOM,

    /** Emergency keyword detected — immediate escalation language */
    MEDICAL_EMERGENCY,

    /** Personal health information detected in message */
    PHI_DETECTED,

    /** Prompt injection, jailbreak, or spam detected */
    ABUSE_OR_SPAM,

    /** Could not classify confidently — cautious support answer */
    UNKNOWN
}
