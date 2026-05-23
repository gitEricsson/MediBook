package com.medibook.domain.copilot.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.common.encryption.PhiAttributeConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "visit_copilot_sessions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VisitCopilotSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telemedicine_session_id", nullable = false, unique = true)
    private Long telemedicineSessionId;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "transcript", columnDefinition = "MEDIUMTEXT")
    private String transcript;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "brief_json", columnDefinition = "MEDIUMTEXT")
    private String briefJson;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "red_flags", columnDefinition = "TEXT")
    private String redFlags;

    @Column(name = "finalized", nullable = false)
    @Builder.Default
    private boolean finalized = false;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Column(name = "consultation_note_id")
    private Long consultationNoteId;
}
