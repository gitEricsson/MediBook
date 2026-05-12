package com.medibook.chat.entity;

import com.medibook.common.audit.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat_conversations",
       indexes = {
           @Index(name = "idx_cc_appointment", columnList = "appointment_id"),
           @Index(name = "idx_cc_patient",     columnList = "patient_id"),
           @Index(name = "idx_cc_doctor",      columnList = "doctor_id"),
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatConversation extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "twilio_conversation_sid", nullable = false, unique = true, length = 64)
    private String twilioConversationSid;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConversationStatus status = ConversationStatus.ACTIVE;

    @Column(name = "ai_enabled", nullable = false)
    @Builder.Default
    private boolean aiEnabled = false;

    @Column(name = "intake_completed", nullable = false)
    @Builder.Default
    private boolean intakeCompleted = false;

    public enum ConversationStatus { ACTIVE, CLOSED, ARCHIVED }
}
