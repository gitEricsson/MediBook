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

    // Emergency / billing lifecycle
    public static final String EMERGENCY_EVENTS        = "emergency.events";
    public static final String OUTSTANDING_BALANCE_EVENTS = "outstanding.balance.events";
    public static final String REFUND_EVENTS           = "refund.events";

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
    public static final String APPOINTMENT_EVENTS_DLQ       = "appointment.events.dlq";
    public static final String PAYMENT_EVENTS_DLQ           = "payment.events.dlq";
    public static final String NOTIFICATION_EVENTS_DLQ      = "notification.events.dlq";
    public static final String AUDIT_EVENTS_DLQ             = "audit.events.dlq";
    public static final String EMERGENCY_EVENTS_DLQ         = "emergency.events.dlq";
    public static final String OUTSTANDING_BALANCE_EVENTS_DLQ = "outstanding.balance.events.dlq";
    public static final String REFUND_EVENTS_DLQ            = "refund.events.dlq";

    /**
     * Appointment lifecycle event types published on {@link #APPOINTMENT_EVENTS}.
     * Centralised here so producers and consumers share the same string constants.
     */
    public static final class AppointmentEventTypes {
        private AppointmentEventTypes() {}

        public static final String BOOKED                     = "BOOKED";
        public static final String CONFIRMED                  = "CONFIRMED";
        public static final String CHECKED_IN                 = "STATUS_CHANGED_TO_CHECKED_IN";
        public static final String IN_WAITING_ROOM            = "STATUS_CHANGED_TO_IN_WAITING_ROOM";
        public static final String IN_CONSULTATION            = "STATUS_CHANGED_TO_IN_CONSULTATION";
        public static final String COMPLETED                  = "STATUS_CHANGED_TO_COMPLETED";
        public static final String CANCELLED                  = "CANCELLED";
        public static final String NO_SHOW                    = "STATUS_CHANGED_TO_NO_SHOW";
        public static final String REFUNDED                   = "STATUS_CHANGED_TO_REFUNDED";
        public static final String EMERGENCY_CONSULTATION_REQUESTED = "EMERGENCY_CONSULTATION_REQUESTED";
        public static final String EMERGENCY_PAYMENT_SETTLED  = "EMERGENCY_PAYMENT_SETTLED";
        public static final String OUTSTANDING_BALANCE_CREATED = "OUTSTANDING_BALANCE_CREATED";
    }
}
