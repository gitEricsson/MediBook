package com.medibook.domain.waitlist.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "waitlist_entries",
        indexes = {
            @Index(name = "idx_wl_status_created", columnList = "status, created_at"),
            @Index(name = "idx_wl_doctor_status",  columnList = "doctor_id, status"),
            @Index(name = "idx_wl_patient",        columnList = "patient_id")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WaitlistEntry extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private User patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(length = 150)
    private String specialization;

    @Column(name = "preferred_date")
    private LocalDate preferredDate;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "WAITING";

    @Column(name = "promoted_at")
    private LocalDateTime promotedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_appointment_id")
    private Appointment promotedAppointment;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
