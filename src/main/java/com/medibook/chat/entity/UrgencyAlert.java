package com.medibook.chat.entity;

import com.medibook.common.audit.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "urgency_alerts",
       indexes = {
           @Index(name = "idx_ua_conversation", columnList = "conversation_id"),
           @Index(name = "idx_ua_patient",      columnList = "patient_id"),
           @Index(name = "idx_ua_acknowledged", columnList = "acknowledged_at"),
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UrgencyAlert extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    /** JSON array of matched urgency keywords */
    @Column(name = "urgency_keywords", nullable = false, columnDefinition = "TEXT")
    private String urgencyKeywords;

    /** Message sent to patient in Twilio */
    @Column(name = "alert_message", nullable = false, columnDefinition = "TEXT")
    private String alertMessage;

    @Column(name = "escalated", nullable = false)
    @Builder.Default
    private boolean escalated = true;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;
}
