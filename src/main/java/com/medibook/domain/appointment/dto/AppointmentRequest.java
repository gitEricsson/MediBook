package com.medibook.domain.appointment.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.entity.ConsultationMedium;

@Data
public class AppointmentRequest {

    private String holdId;

    @NotNull(message = "Appointment type is required")
    private AppointmentType type = AppointmentType.IN_PERSON;

    /** PHYSICAL / AUDIO / VIDEO — determines telemedicine eligibility */
    @NotNull(message = "Consultation medium is required")
    private ConsultationMedium consultationMedium = ConsultationMedium.PHYSICAL;

    /** FIRST_VISIT / FOLLOW_UP / EMERGENCY */
    @NotNull(message = "Consultation type is required")
    private AppointmentType consultationType = AppointmentType.FIRST_VISIT;

    /** Required to be true when consultationType == FOLLOW_UP */
    private boolean followUpConsentGiven = false;

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
