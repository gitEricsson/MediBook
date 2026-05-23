package com.medibook.domain.prescription.entity;

import com.medibook.common.audit.SoftDeleteEntity;
import com.medibook.common.encryption.PhiAttributeConverter;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;

@Entity
@Table(name = "prescriptions")
@SQLDelete(sql = "UPDATE prescriptions SET deleted_at = CURRENT_TIMESTAMP, deleted_by = ?1 WHERE id = ?2")
@Where(clause = "deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Prescription extends SoftDeleteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private User patient;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "drug_name", nullable = false, columnDefinition = "TEXT")
    private String drugName;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String dosage;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(columnDefinition = "TEXT")
    private String route;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String frequency;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(columnDefinition = "MEDIUMTEXT")
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PrescriptionStatus status = PrescriptionStatus.ACTIVE;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "cancelled_reason", columnDefinition = "TEXT")
    private String cancelledReason;
}
