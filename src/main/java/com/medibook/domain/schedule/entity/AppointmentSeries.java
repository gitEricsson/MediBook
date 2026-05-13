package com.medibook.domain.schedule.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "appointment_series")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppointmentSeries extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private User patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Column(name = "recurrence_type", nullable = false, length = 20)
    private String recurrenceType;

    @Column(name = "recurrence_interval", nullable = false)
    @Builder.Default
    private int recurrenceInterval = 1;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "max_occurrences")
    private Integer maxOccurrences;

    @Column(name = "time_of_day", nullable = false)
    private LocalTime timeOfDay;

    @Column(name = "duration_mins", nullable = false)
    @Builder.Default
    private int durationMins = 30;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "appointment_type", nullable = false, length = 20)
    @Builder.Default
    private AppointmentType appointmentType = AppointmentType.IN_PERSON;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";
}
