package com.medibook.domain.schedule.entity;

import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A single-day, time-bounded unavailability declared by a doctor — distinct
 * from {@link DoctorLeave} which is a whole-day-or-more leave request. Reasons
 * are required so admins have an audit trail (e.g. "operating on patient X").
 *
 * The availability grid in {@code DoctorSearchService.getAvailability} marks
 * any slot whose window overlaps a block as {@code BLOCKED} so patients can't
 * book it.
 */
@Entity
@Table(name = "doctor_slot_blocks",
        indexes = {
            @Index(name = "idx_slot_block_doctor_date", columnList = "doctor_id, block_date"),
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DoctorSlotBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Column(name = "block_date", nullable = false)
    private LocalDate blockDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(nullable = false, length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
