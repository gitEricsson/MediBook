package com.medibook.domain.doctor.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class DoctorRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotNull(message = "Department ID is required")
    private Long departmentId;

    /** Additional departments this doctor is cross-listed in (optional). */
    private List<Long> additionalDepartmentIds;

    @Size(max = 150)
    private String specialization;

    /** All specializations, including the primary one. The primary is always
     *  derived from {@code specialization}; any extras go here. */
    private List<@Size(max = 150) String> specializations;

    @NotBlank(message = "License number is required")
    @Size(max = 100)
    private String licenseNumber;

    @Size(max = 2000)
    private String bio;

    @Min(value = 10, message = "Slot duration must be at least 10 minutes")
    @Max(value = 120, message = "Slot duration cannot exceed 120 minutes")
    private Integer slotDurationMins;

    @Min(value = 0, message = "Years of experience cannot be negative")
    @Max(value = 60, message = "Years of experience cannot exceed 60")
    private Integer yearsOfExperience;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal consultationFee;

    @Pattern(regexp = "MALE|FEMALE|OTHER", message = "Gender must be MALE, FEMALE, or OTHER")
    private String gender;

    @Size(max = 255)
    private String languages;
}
