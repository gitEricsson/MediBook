package com.medibook.domain.emergency.dto;

import com.medibook.domain.appointment.entity.ConsultationMedium;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EmergencyConsultationRequest {

    @NotNull(message = "Consultation medium is required")
    private ConsultationMedium medium = ConsultationMedium.VIDEO;

    @NotBlank(message = "Symptom description is required")
    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String symptoms;

    /** Optional preferred department ID. System assigns doctor if omitted. */
    private Long departmentId;

    /**
     * CRITICAL flag bypasses outstanding balance enforcement for life-threatening cases.
     * Requires admin confirmation in post-consultation settlement.
     */
    private boolean criticalOverride = false;
}
