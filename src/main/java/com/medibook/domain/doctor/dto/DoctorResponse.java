package com.medibook.domain.doctor.dto;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.config.HospitalProperties;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    /** All department IDs: primary + additional. */
    private List<Long> departmentIds;
    /** All department names: primary + additional. */
    private List<String> departmentNames;
    /** All specializations: primary + additional. Empty list if none set. */
    private List<String> specializations;
    private String languages;
    private boolean acceptingNew;
    /** True when the doctor profile is active (admin has not deactivated it). */
    private boolean isActive;
    private int slotDurationMins;
    private int yearsOfExperience;
    private BigDecimal consultationFee;
    private boolean seniorConsultant;
    private String gender;
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
        // Base fee comes from department (policy-driven); surcharges are applied at booking time.
        java.math.BigDecimal baseFee = d.getDepartment().getBaseConsultationFee();
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
                .departmentIds(buildDeptIds(d))
                .departmentNames(buildDeptNames(d))
                .specializations(buildSpecializations(d))
                .languages(d.getLanguages())
                .acceptingNew(d.isAcceptingNew())
                .isActive(d.isActive())
                .slotDurationMins(d.getSlotDurationMins())
                .yearsOfExperience(d.getYearsOfExperience())
                .consultationFee(baseFee)
                .seniorConsultant(senior)
                .gender(d.getGender())
                .createdAt(d.getCreatedAt())
                .build();
    }

    private static List<Long> buildDeptIds(Doctor d) {
        List<Long> ids = new ArrayList<>();
        ids.add(d.getDepartment().getId());
        if (d.getAdditionalDepartments() != null) {
            d.getAdditionalDepartments().stream()
                    .map(Department::getId)
                    .filter(id -> !ids.contains(id))
                    .forEach(ids::add);
        }
        return ids;
    }

    private static List<String> buildDeptNames(Doctor d) {
        List<String> names = new ArrayList<>();
        names.add(d.getDepartment().getName());
        if (d.getAdditionalDepartments() != null) {
            d.getAdditionalDepartments().stream()
                    .map(Department::getName)
                    .filter(n -> !names.contains(n))
                    .forEach(names::add);
        }
        return names;
    }

    private static List<String> buildSpecializations(Doctor d) {
        List<String> specs = new ArrayList<>();
        if (d.getSpecialization() != null && !d.getSpecialization().isBlank()) {
            specs.add(d.getSpecialization());
        }
        if (d.getSpecializations() != null) {
            d.getSpecializations().stream()
                    .filter(s -> s != null && !s.isBlank() && !specs.contains(s))
                    .forEach(specs::add);
        }
        return specs;
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
                .departmentIds(buildDeptIds(d))
                .departmentNames(buildDeptNames(d))
                .specializations(buildSpecializations(d))
                .languages(d.getLanguages())
                .acceptingNew(d.isAcceptingNew())
                .isActive(d.isActive())
                .slotDurationMins(d.getSlotDurationMins())
                .yearsOfExperience(d.getYearsOfExperience())
                .consultationFee(d.getEffectiveConsultationFee())
                .seniorConsultant(senior)
                .gender(d.getGender())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
