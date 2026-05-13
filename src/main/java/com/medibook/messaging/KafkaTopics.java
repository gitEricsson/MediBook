package com.medibook.messaging;

public final class KafkaTopics {

    private KafkaTopics() {}

    // Core
    public static final String APPOINTMENT_EVENTS  = "appointment.events";
    public static final String AUDIT_EVENTS        = "audit.events";
    public static final String NOTIFICATION_EVENTS = "notification.events";

    // Payments
    public static final String PAYMENT_EVENTS      = "payment.events";

    // Telemedicine
    public static final String TELEMEDICINE_EVENTS = "telemedicine.events";

    // Chat / AI
    public static final String CHAT_EVENTS          = "chat.events";
    public static final String AI_EVENTS            = "ai.events";
    public static final String URGENCY_EVENTS       = "urgency.events";

    // Reviews
    public static final String REVIEW_EVENTS       = "review.events";

    // Waitlist
    public static final String WAITLIST_EVENTS     = "waitlist.events";

    // Consent
    public static final String CONSENT_EVENTS      = "consent.events";

    // Dead Letter Queues
    public static final String APPOINTMENT_EVENTS_DLQ  = "appointment.events.dlq";
    public static final String PAYMENT_EVENTS_DLQ      = "payment.events.dlq";
    public static final String NOTIFICATION_EVENTS_DLQ = "notification.events.dlq";
    public static final String AUDIT_EVENTS_DLQ        = "audit.events.dlq";
}
