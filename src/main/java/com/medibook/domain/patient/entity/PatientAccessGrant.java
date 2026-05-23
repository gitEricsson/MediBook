package com.medibook.domain.patient.entity;

import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "patient_access_grants", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"patient_id", "doctor_id"}, name = "uk_patient_doctor_grant")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientAccessGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private User patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private AccessGrantStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime grantedAt;

    @Column
    private LocalDateTime revokedAt;

    @Column(length = 500)
    private String reason;

    /**
     * Inclusive upper bound for which patient records this doctor may view.
     * Set when the grant was auto-created from a FOLLOW_UP consent — the
     * patient agreed to share history up to (and including) this date only.
     * {@code null} means no cutoff (legacy approval flow / manual grant).
     */
    @Column(name = "access_up_to_date")
    private LocalDate accessUpToDate;

    public enum AccessGrantStatus {
        PENDING,
        APPROVED,
        REVOKED
    }
}
