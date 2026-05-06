package com.medibook.domain.doctor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DoctorRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotNull(message = "Department ID is required")
    private Long departmentId;

    @Size(max = 150)
    private String specialization;

    @NotBlank(message = "License number is required")
    @Size(max = 100)
    private String licenseNumber;

    @Size(max = 2000)
    private String bio;
}
