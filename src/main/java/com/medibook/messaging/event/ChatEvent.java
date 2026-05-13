package com.medibook.messaging.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kafka event for chat domain.
 *
 * eventType values:
 *   CHAT_MESSAGE_RECEIVED    — patient or doctor sent a message
 *   AI_RESPONSE_CREATED      — AI posted a message to the conversation
 *   AI_DRAFT_CREATED         — AI created a draft for doctor review
 *   AI_SUMMARY_CREATED       — doctor requested a conversation summary
 *   MEDICAL_URGENCY_FLAGGED  — urgent symptoms detected; escalation sent
 *   DOCTOR_ESCALATION_REQUIRED — clinical query flagged; awaiting doctor
 *   AI_CONSENT_GRANTED       — patient granted AI participation consent
 *   AI_CONSENT_REVOKED       — patient revoked AI participation consent
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEvent {

    private String        eventId;
    private String        eventType;

    private Long          conversationId;
    private Long          appointmentId;
    private Long          patientId;
    private Long          doctorId;

    /** For MEDICAL_URGENCY_FLAGGED: matched keyword summary */
    private String        urgencyKeywords;

    /** For AI_DRAFT_CREATED: the draft response id */
    private Long          draftId;

    /** Safety label at time of event */
    private String        safetyLabel;

    private LocalDateTime occurredAt;
}
