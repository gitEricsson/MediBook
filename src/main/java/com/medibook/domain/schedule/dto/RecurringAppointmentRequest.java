package com.medibook.domain.schedule.dto;

import com.medibook.domain.appointment.entity.AppointmentType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class RecurringAppointmentRequest {

    @NotNull
    private Long doctorId;

    @NotNull
    @Pattern(regexp = "DAILY|WEEKLY|MONTHLY")
    private String recurrenceType;

    @Min(1)
    private int recurrenceInterval = 1;

    @NotNull
    private LocalDate startDate;

    private LocalDate endDate;

    @Min(1)
    private Integer maxOccurrences;

    @NotNull
    private LocalTime timeOfDay;

    @Min(10)
    private int durationMins = 30;

    private AppointmentType appointmentType = AppointmentType.IN_PERSON;

    private String reason;
}
