package com.medibook.domain.doctor.dto;

import com.medibook.config.HospitalProperties;
import com.medibook.domain.doctor.entity.Doctor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Doctor response for admin/super-admin consumers only.
 * Includes internal operational metrics (rating, review count) that must not
 * appear in patient-facing responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorAdminResponse {

    private Long id;
    private Long userId;
    private String fullName;
    private String email;
    private String specialization;
    private String licenseNumber;
    private String bio;
    private Long departmentId;
    private String departmentName;
    private String languages;
    private boolean acceptingNew;
    private int slotDurationMins;
    private int yearsOfExperience;
    private BigDecimal consultationFee;
    private boolean seniorConsultant;
    private String gender;
    private LocalDateTime createdAt;

    // ── Internal operational metrics — ADMIN-ONLY ────────────────────────────
    private double averageRating;
    private int reviewCount;

    public static DoctorAdminResponse fromEntity(Doctor d, HospitalProperties hospitalProps) {
        boolean senior = hospitalProps.isSeniorConsultant(d.getYearsOfExperience());
        BigDecimal baseFee = d.getDepartment().getBaseConsultationFee();
        return DoctorAdminResponse.builder()
                .id(d.getId())
                .userId(d.getUser().getId())
                .fullName(d.getUser().getFullName())
                .email(d.getUser().getEmail())
                .specialization(d.getSpecialization())
                .licenseNumber(d.getLicenseNumber())
                .bio(d.getBio())
                .departmentId(d.getDepartment().getId())
                .departmentName(d.getDepartment().getName())
                .languages(d.getLanguages())
                .acceptingNew(d.isAcceptingNew())
                .slotDurationMins(d.getSlotDurationMins())
                .yearsOfExperience(d.getYearsOfExperience())
                .consultationFee(baseFee)
                .seniorConsultant(senior)
                .gender(d.getGender())
                .createdAt(d.getCreatedAt())
                .averageRating(d.getAverageRating())
                .reviewCount(d.getReviewCount())
                .build();
    }

    public static DoctorAdminResponse fromPublic(DoctorResponse pub, double averageRating, int reviewCount) {
        return DoctorAdminResponse.builder()
                .id(pub.getId())
                .userId(pub.getUserId())
                .fullName(pub.getFullName())
                .email(pub.getEmail())
                .specialization(pub.getSpecialization())
                .licenseNumber(pub.getLicenseNumber())
                .bio(pub.getBio())
                .departmentId(pub.getDepartmentId())
                .departmentName(pub.getDepartmentName())
                .languages(pub.getLanguages())
                .acceptingNew(pub.isAcceptingNew())
                .slotDurationMins(pub.getSlotDurationMins())
                .yearsOfExperience(pub.getYearsOfExperience())
                .consultationFee(pub.getConsultationFee())
                .seniorConsultant(pub.isSeniorConsultant())
                .gender(pub.getGender())
                .createdAt(pub.getCreatedAt())
                .averageRating(averageRating)
                .reviewCount(reviewCount)
                .build();
    }
}
