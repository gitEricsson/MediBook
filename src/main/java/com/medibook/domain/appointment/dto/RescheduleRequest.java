package com.medibook.domain.appointment.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RescheduleRequest {
    @NotNull(message = "New start time is required")
    @Future(message = "New start time must be in the future")
    private LocalDateTime newStart;
    
    @NotNull(message = "New end time is required")
    @Future(message = "New end time must be in the future")
    private LocalDateTime newEnd;

    private String holdId;
}
