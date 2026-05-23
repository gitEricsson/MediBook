package com.medibook.domain.appointment.dto;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentResponse {

    private Long id;
    private Long patientId;
    private String patientName;
    private Long doctorId;
    private String doctorName;
    private String departmentName;
    private LocalDateTime scheduledAt;
    private LocalDateTime endTime;
    private int durationMins;
    private AppointmentStatus status;
    private AppointmentType type;
    private ConsultationMedium consultationMedium;
    private AppointmentType consultationType;
    private boolean followUpConsentGiven;
    private String confirmationCode;
    private String reason;
    private String notes;
    private BigDecimal consultationFee;
    private LocalDateTime createdAt;

    /**
     * Outstanding amount the patient still owes for this appointment, if any.
     * Null when no invoice exists or the invoice is already PAID. Surfaced so
     * the FE can render an "outstanding payment" CTA on the consultation card
     * even when the appointment status itself doesn't carry that signal
     * (e.g. COMPLETED emergency appointments awaiting post-consult settlement).
     */
    private BigDecimal outstandingBalance;

    public static AppointmentResponse fromEntity(Appointment a) {
        return fromEntity(a, null);
    }

    public static AppointmentResponse fromEntity(Appointment a, BigDecimal outstandingBalance) {
        return AppointmentResponse.builder()
                .id(a.getId())
                .patientId(a.getPatient().getId())
                .patientName(a.getPatient().getFullName())
                .doctorId(a.getDoctor().getId())
                .doctorName(a.getDoctor().getUser().getFullName())
                .departmentName(a.getDoctor().getDepartment().getName())
                .scheduledAt(a.getScheduledAt())
                .endTime(a.getEndTime())
                .durationMins(a.getDurationMins())
                .status(a.getStatus())
                .type(a.getType())
                .consultationMedium(a.getConsultationMedium())
                .consultationType(a.getConsultationType())
                .followUpConsentGiven(a.isFollowUpConsentGiven())
                .confirmationCode(a.getConfirmationCode())
                .reason(a.getReason())
                .notes(a.getNotes())
                .consultationFee(a.getConsultationFee())
                .createdAt(a.getCreatedAt())
                .outstandingBalance(outstandingBalance)
                .build();
    }
}
