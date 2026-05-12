package com.medibook.chat.entity;

import com.medibook.common.audit.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_draft_responses",
       indexes = {
           @Index(name = "idx_adr_conversation", columnList = "conversation_id"),
           @Index(name = "idx_adr_doctor",       columnList = "doctor_id"),
           @Index(name = "idx_adr_status",       columnList = "status"),
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiDraftResponse extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    /** Original AI-generated body — immutable after creation */
    @Column(name = "draft_body", nullable = false, columnDefinition = "TEXT")
    private String draftBody;

    /** Doctor's edited version — null until the doctor edits */
    @Column(name = "edited_body", columnDefinition = "TEXT")
    private String editedBody;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DraftStatus status = DraftStatus.PENDING;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Column(name = "model_used", nullable = false, length = 60)
    private String modelUsed;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    public enum DraftStatus { PENDING, APPROVED, REJECTED, SENT }

    /** Returns editedBody if present, else the original draft */
    public String getEffectiveBody() {
        return (editedBody != null && !editedBody.isBlank()) ? editedBody : draftBody;
    }
}
