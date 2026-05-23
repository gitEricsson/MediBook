package com.medibook.domain.emergency.dto;

import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class EmergencyConsultationResponse {
    Long appointmentId;
    Long doctorId;
    String doctorName;
    String departmentName;
    AppointmentStatus status;
    ConsultationMedium medium;
    String confirmationCode;
    LocalDateTime createdAt;
    /** Telemedicine session ID — non-null when medium is AUDIO or VIDEO */
    Long sessionId;
    /** Fee computed at booking time (EMERGENCY type + medium surcharge + senior surcharge). */
    BigDecimal consultationFee;
    String message;
}
