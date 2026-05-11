package com.medibook.domain.telemedicine.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.appointment.entity.Appointment;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "telemedicine_sessions",
        indexes = {
            @Index(name = "idx_ts_status", columnList = "status")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TelemedicineSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false, unique = true)
    private Appointment appointment;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private TelemedicineSessionStatus status = TelemedicineSessionStatus.SCHEDULED;

    @Column(name = "room_id", length = 255)
    private String roomId;

    @Column(name = "join_url_patient", length = 1024)
    private String joinUrlPatient;

    @Column(name = "join_url_doctor", length = 1024)
    private String joinUrlDoctor;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "call_note_draft", columnDefinition = "MEDIUMTEXT")
    private String callNoteDraft;

    @Column(name = "ai_assisted", nullable = false)
    @Builder.Default
    private boolean aiAssisted = false;

    @Column(name = "doctor_reviewed", nullable = false)
    @Builder.Default
    private boolean doctorReviewed = false;

    @Column(name = "patient_consent", nullable = false)
    @Builder.Default
    private boolean patientConsent = false;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ChatMessage> chatMessages = new ArrayList<>();
}
