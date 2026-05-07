package com.medibook.domain.doctor.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class WorkingHoursRequest {

    @NotEmpty(message = "At least one schedule entry is required")
    @Valid
    private List<DaySchedule> schedule;

    @Data
    public static class DaySchedule {

        @NotNull(message = "Day of week is required")
        @Min(value = 1, message = "Day of week must be between 1 (Mon) and 7 (Sun)")
        @Max(value = 7, message = "Day of week must be between 1 (Mon) and 7 (Sun)")
        private Integer dayOfWeek;

        @NotNull(message = "Start time is required")
        private LocalTime startTime;

        @NotNull(message = "End time is required")
        private LocalTime endTime;
    }
}
