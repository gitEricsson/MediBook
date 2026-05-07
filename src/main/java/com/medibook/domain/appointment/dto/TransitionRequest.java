package com.medibook.domain.appointment.dto;

import com.medibook.domain.appointment.entity.AppointmentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransitionRequest {
    @NotNull(message = "Target status is required")
    private AppointmentStatus to;
    private String reason;
}
