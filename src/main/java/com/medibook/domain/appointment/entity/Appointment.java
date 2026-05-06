package com.medibook.domain.appointment.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "appointments",
        indexes = {
            @Index(name = "idx_appt_patient",   columnList = "patient_id"),
            @Index(name = "idx_appt_doctor",    columnList = "doctor_id"),
            @Index(name = "idx_appt_status",    columnList = "status"),
            @Index(name = "idx_appt_scheduled", columnList = "scheduled_at")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Appointment extends AuditableEntity {

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
    @Column(nullable = false, length = 30)
    @Builder.Default
    private AppointmentStatus status = AppointmentStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String reason;

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
}
