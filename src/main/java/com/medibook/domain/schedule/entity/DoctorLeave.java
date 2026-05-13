package com.medibook.domain.schedule.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "doctor_leaves",
        indexes = {
            @Index(name = "idx_dl_doctor_dates", columnList = "doctor_id, start_date, end_date")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DoctorLeave extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(length = 255)
    private String reason;

    @Column(name = "leave_type", nullable = false, length = 30)
    @Builder.Default
    private String leaveType = "PERSONAL";

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "APPROVED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;
}
