package com.medibook.chat.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.ai.safety.SafetyLabel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat_messages",
       indexes = {
           @Index(name = "idx_cm_conversation", columnList = "conversation_id"),
           @Index(name = "idx_cm_sender",       columnList = "sender_id"),
           @Index(name = "idx_cm_safety",       columnList = "safety_label"),
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessage extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "twilio_message_sid", nullable = false, unique = true, length = 64)
    private String twilioMessageSid;

    /** NULL for AI_ASSISTANT and SYSTEM messages */
    @Column(name = "sender_id")
    private Long senderId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sender_role", nullable = false, length = 20)
    private SenderRole senderRole;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "ai_generated", nullable = false)
    @Builder.Default
    private boolean aiGenerated = false;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "safety_label", length = 30)
    private SafetyLabel safetyLabel;

    public enum SenderRole { PATIENT, DOCTOR, AI_ASSISTANT, SYSTEM }
}
