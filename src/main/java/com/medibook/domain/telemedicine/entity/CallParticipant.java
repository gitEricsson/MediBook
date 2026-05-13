package com.medibook.domain.telemedicine.entity;

import com.medibook.common.audit.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "call_participants",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_call_participant_session_user",
                        columnNames = {"telemedicine_session_id", "user_id"})
        },
        indexes = {
                @Index(name = "idx_cp_session", columnList = "telemedicine_session_id"),
                @Index(name = "idx_cp_user", columnList = "user_id")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CallParticipant extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telemedicine_session_id", nullable = false)
    private TelemedicineSession session;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private CallParticipantRole role;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(name = "camera_enabled", nullable = false)
    @Builder.Default
    private boolean cameraEnabled = true;

    @Column(name = "microphone_enabled", nullable = false)
    @Builder.Default
    private boolean microphoneEnabled = true;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "connection_status", nullable = false, length = 30)
    @Builder.Default
    private CallConnectionStatus connectionStatus = CallConnectionStatus.INVITED;
}
