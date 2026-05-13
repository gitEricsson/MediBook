package com.medibook.ai.audit.entity;

import com.medibook.ai.safety.SafetyLabel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Append-only audit record for every AI call.
 * NEVER add UPDATE or DELETE endpoints for this table.
 */
@Entity
@Table(name = "ai_message_audit",
       indexes = {
           @Index(name = "idx_ama_conversation", columnList = "conversation_id"),
           @Index(name = "idx_ama_actor",        columnList = "actor_id"),
           @Index(name = "idx_ama_operation",    columnList = "operation"),
           @Index(name = "idx_ama_created",      columnList = "created_at"),
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@EntityListeners(AuditingEntityListener.class)
public class AiMessageAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, columnDefinition = "CHAR(36)")
    private String eventId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "actor_role", nullable = false, length = 20)
    private String actorRole;

    @Column(name = "operation", nullable = false, length = 30)
    private String operation;   // INTAKE | SUMMARY | DRAFT | ASSISTANT | URGENCY_CHECK

    @Column(name = "model_used", nullable = false, length = 60)
    private String modelUsed;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Column(name = "input_token_count")
    private Integer inputTokenCount;

    @Column(name = "output_token_count")
    private Integer outputTokenCount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "safety_label", nullable = false, length = 30)
    private SafetyLabel safetyLabel;

    @Column(name = "escalation_triggered", nullable = false)
    @Builder.Default
    private boolean escalationTriggered = false;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
