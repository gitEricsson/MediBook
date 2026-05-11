package com.medibook.domain.review.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "doctor_reviews",
        indexes = {
            @Index(name = "idx_rev_doctor_status", columnList = "doctor_id, status"),
            @Index(name = "idx_rev_patient",       columnList = "patient_id")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DoctorReview extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false, unique = true)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private User patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Column(nullable = false)
    private byte rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING_MODERATION";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderated_by")
    private User moderatedBy;

    @Column(name = "moderated_at")
    private LocalDateTime moderatedAt;
}
