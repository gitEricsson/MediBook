package com.medibook.domain.doctor.dto;

import com.medibook.domain.doctor.entity.Doctor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class DoctorResponse {

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
    private BigDecimal effectiveConsultationFee;
    private String gender;
    private boolean telemedicineEnabled;
    private double averageRating;
    private int reviewCount;
    private LocalDateTime createdAt;

    public static DoctorResponse fromEntity(Doctor d) {
        return DoctorResponse.builder()
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
                .consultationFee(d.getConsultationFee())
                .effectiveConsultationFee(d.getEffectiveConsultationFee())
                .gender(d.getGender())
                .telemedicineEnabled(d.isTelemedicineEnabled())
                .averageRating(d.getAverageRating())
                .reviewCount(d.getReviewCount())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
