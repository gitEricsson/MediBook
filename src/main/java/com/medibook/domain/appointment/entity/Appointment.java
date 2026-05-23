package com.medibook.domain.appointment.entity;

import com.medibook.common.audit.SoftDeleteEntity;
import com.medibook.common.encryption.PhiAttributeConverter;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.schedule.entity.AppointmentSeries;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "appointments",
        indexes = {
            @Index(name = "idx_appt_patient",   columnList = "patient_id"),
            @Index(name = "idx_appt_doctor",    columnList = "doctor_id"),
            @Index(name = "idx_appt_status",    columnList = "status"),
            @Index(name = "idx_appt_scheduled", columnList = "scheduled_at")
        })
@SQLDelete(sql = "UPDATE appointments SET deleted_at = CURRENT_TIMESTAMP, deleted_by = ?1 WHERE id = ?2")
@SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Appointment extends SoftDeleteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private User patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "duration_mins", nullable = false)
    @Builder.Default
    private int durationMins = 30;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private AppointmentStatus status = AppointmentStatus.PENDING;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Column(name = "slot_key", insertable = false, updatable = false)
    private String slotKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "appointment_type", nullable = false, length = 20)
    @Builder.Default
    private AppointmentType type = AppointmentType.IN_PERSON;

    /** PHYSICAL / AUDIO / VIDEO — controls whether telemedicine session is gated */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "consultation_medium", nullable = false, length = 20)
    @Builder.Default
    private ConsultationMedium consultationMedium = ConsultationMedium.PHYSICAL;

    /** FIRST_VISIT / FOLLOW_UP / EMERGENCY */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "consultation_type", nullable = false, length = 20)
    @Builder.Default
    private AppointmentType consultationType = AppointmentType.FIRST_VISIT;

    /** True when patient consented to share prior records for a FOLLOW_UP consultation */
    @Column(name = "follow_up_consent_given", nullable = false)
    @Builder.Default
    private boolean followUpConsentGiven = false;

    @Column(name = "consultation_fee", precision = 10, scale = 2)
    private BigDecimal consultationFee;

    @Column(name = "confirmation_code", unique = true, length = 20)
    private String confirmationCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id")
    private AppointmentSeries series;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}
