package com.medibook.domain.appointment.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
    private int durationMins = 30;

    private String reason;
}
