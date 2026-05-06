package com.medibook.domain.appointment.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppointmentRequest {

    @NotNull(message = "Doctor ID is required")
    private Long doctorId;

    @NotNull(message = "Scheduled time is required")
    @Future(message = "Appointment must be scheduled in the future")
    private LocalDateTime scheduledAt;

    @Min(value = 15, message = "Minimum duration is 15 minutes")
    @Max(value = 480, message = "Maximum duration is 8 hours")
    private int durationMins = 30;

    @Size(max = 1000, message = "Reason must not exceed 1000 characters")
    private String reason;
}
