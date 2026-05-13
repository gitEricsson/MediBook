package com.medibook.chat.entity;

import com.medibook.common.audit.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_consent_records",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_acr_patient_conversation",
           columnNames = {"patient_id", "conversation_id"}),
       indexes = {
           @Index(name = "idx_acr_patient",      columnList = "patient_id"),
           @Index(name = "idx_acr_conversation",  columnList = "conversation_id"),
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiConsentRecord extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "consent_granted", nullable = false)
    private boolean consentGranted;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** Verbatim consent text shown to patient at time of consent */
    @Column(name = "consent_text", nullable = false, columnDefinition = "TEXT")
    private String consentText;

    @Column(name = "consent_version", nullable = false, length = 10)
    private String consentVersion;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public boolean isActive() {
        return consentGranted && revokedAt == null;
    }
}
