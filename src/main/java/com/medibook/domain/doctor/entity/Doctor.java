package com.medibook.domain.doctor.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "doctors",
        indexes = {
            @Index(name = "idx_doctors_department",    columnList = "department_id"),
            @Index(name = "idx_doctors_specialization",columnList = "specialization"),
            @Index(name = "idx_doctors_accepting_new", columnList = "accepting_new"),
            @Index(name = "idx_doctors_is_active",     columnList = "is_active"),
            @Index(name = "idx_doctors_telemedicine",  columnList = "telemedicine_enabled, is_active"),
            @Index(name = "idx_doctors_rating",        columnList = "average_rating")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Doctor extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(length = 150)
    private String specialization;

    @Column(name = "license_number", nullable = false, unique = true, length = 100)
    private String licenseNumber;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(length = 255)
    @Builder.Default
    private String languages = "English";

    @Column(name = "accepting_new", nullable = false)
    @Builder.Default
    private boolean acceptingNew = true;

    @Column(name = "slot_duration_mins", nullable = false)
    @Builder.Default
    private int slotDurationMins = 30;

    @Column(name = "search_vector", columnDefinition = "TEXT")
    private String searchVector;

    @Column(name = "years_of_experience", nullable = false)
    @Builder.Default
    private int yearsOfExperience = 0;

    @Column(name = "consultation_fee", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal consultationFee = BigDecimal.ZERO;

    @Column(length = 10)
    private String gender;

    @Column(name = "telemedicine_enabled", nullable = false)
    @Builder.Default
    private boolean telemedicineEnabled = false;

    @Column(name = "average_rating", nullable = false)
    @Builder.Default
    private double averageRating = 0.0;

    @Column(name = "review_count", nullable = false)
    @Builder.Default
    private int reviewCount = 0;

    public BigDecimal getEffectiveConsultationFee() {
        BigDecimal base = department != null && department.getBaseConsultationFee() != null
                ? department.getBaseConsultationFee()
                : BigDecimal.valueOf(5000);
        BigDecimal premium = yearsOfExperience > 10
                ? base.multiply(BigDecimal.valueOf(0.20))
                : BigDecimal.ZERO;
        BigDecimal override = consultationFee != null && consultationFee.compareTo(BigDecimal.ZERO) > 0
                ? consultationFee
                : BigDecimal.ZERO;
        return override.compareTo(BigDecimal.ZERO) > 0
                ? override
                : base.add(premium);
    }
}
