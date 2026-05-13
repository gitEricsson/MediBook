package com.medibook.domain.patient.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AccessGrantRequest {

    @NotNull(message = "Doctor ID is required")
    private Long doctorId;

    @Size(max = 500, message = "Reason cannot exceed 500 characters")
    private String reason;
}
