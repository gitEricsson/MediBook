package com.medibook.domain.consent.entity;

import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_consents",
        indexes = {
            @Index(name = "idx_uc_type", columnList = "consent_type")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "consent_type", nullable = false, length = 50)
    private String consentType;

    @Column(nullable = false)
    private boolean granted;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
