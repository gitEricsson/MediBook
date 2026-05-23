package com.medibook.domain.survey.entity;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.LocalDateTime;

/**
 * Private post-consultation feedback collected from patients after a COMPLETED appointment.
 * Survey responses are strictly internal — visible only to admin/super-admin for QA analytics.
 * One survey per appointment (enforced by the unique constraint on appointment_id).
 */
@Entity
@Table(name = "consultation_surveys",
        indexes = {
            @Index(name = "idx_survey_doctor",  columnList = "doctor_id"),
            @Index(name = "idx_survey_patient", columnList = "patient_id"),
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConsultationSurvey {

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

    /** Overall satisfaction 1–5 */
    @JdbcTypeCode(Types.TINYINT)
    @Column(name = "overall_satisfaction", nullable = false)
    private byte overallSatisfaction;

    /** Doctor communication quality 1–5 */
    @JdbcTypeCode(Types.TINYINT)
    @Column(name = "communication_quality", nullable = false)
    private byte communicationQuality;

    /** Wait time experience 1–5 */
    @JdbcTypeCode(Types.TINYINT)
    @Column(name = "wait_time_experience", nullable = false)
    private byte waitTimeExperience;

    /** Likelihood to recommend 1–5 (NPS proxy) */
    @JdbcTypeCode(Types.TINYINT)
    @Column(name = "recommend_likelihood", nullable = false)
    private byte recommendLikelihood;

    /** Free-text comments — private, admin-only */
    @Column(name = "private_comments", columnDefinition = "TEXT")
    private String privateComments;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;
}
