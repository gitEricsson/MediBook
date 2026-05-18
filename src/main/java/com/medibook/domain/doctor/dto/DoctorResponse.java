package com.medibook.domain.doctor.dto;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.config.HospitalProperties;
import com.medibook.domain.doctor.entity.Doctor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@lombok.NoArgsConstructor   // Jackson needs a default constructor to deserialize cached entries from Redis.
@lombok.AllArgsConstructor  // Keep builder + canonical constructor in sync.
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
    private boolean seniorConsultant;
    private String gender;
    private boolean telemedicineEnabled;
    private double averageRating;
    private int reviewCount;
    private LocalDateTime createdAt;

    /**
     * Build response using hospital-wide pricing.
     * Booking fees are classified strictly by specialization — every doctor in the same
     * specialization charges the same amount. The {@code seniorConsultant} flag is exposed
     * for UI badging only and no longer affects the fee.
     */
    public static DoctorResponse fromEntity(Doctor d, HospitalProperties hospitalProps) {
        if (d.getUser() == null) {
            throw new ResourceNotFoundException("User account", "doctor.id", d.getId());
        }
        if (d.getDepartment() == null) {
            throw new ResourceNotFoundException("Department", "doctor.id", d.getId());
        }
        boolean senior = hospitalProps.isSeniorConsultant(d.getYearsOfExperience());
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
                .consultationFee(hospitalProps.getFeeForDoctor(d.getSpecialization(), d.getYearsOfExperience()))
                .seniorConsultant(senior)
                .gender(d.getGender())
                .telemedicineEnabled(d.isTelemedicineEnabled())
                .averageRating(d.getAverageRating())
                .reviewCount(d.getReviewCount())
                .createdAt(d.getCreatedAt())
                .build();
    }

    /** @deprecated Use {@link #fromEntity(Doctor, HospitalProperties)} instead */
    @Deprecated
    public static DoctorResponse fromEntity(Doctor d) {
        if (d.getUser() == null) {
            throw new ResourceNotFoundException("User account", "doctor.id", d.getId());
        }
        if (d.getDepartment() == null) {
            throw new ResourceNotFoundException("Department", "doctor.id", d.getId());
        }
        boolean senior = d.getYearsOfExperience() > 10;
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
                .consultationFee(d.getEffectiveConsultationFee())
                .seniorConsultant(senior)
                .gender(d.getGender())
                .telemedicineEnabled(d.isTelemedicineEnabled())
                .averageRating(d.getAverageRating())
                .reviewCount(d.getReviewCount())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
