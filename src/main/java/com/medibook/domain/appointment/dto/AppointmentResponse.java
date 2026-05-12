package com.medibook.domain.appointment.dto;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private int durationMins;
    private AppointmentStatus status;
    private AppointmentType type;
    private String confirmationCode;
    private String reason;
    private String notes;
    private LocalDateTime createdAt;

    public static AppointmentResponse fromEntity(Appointment a) {
        return AppointmentResponse.builder()
                .id(a.getId())
                .patientId(a.getPatient().getId())
                .patientName(a.getPatient().getFullName())
                .doctorId(a.getDoctor().getId())
                .doctorName(a.getDoctor().getUser().getFullName())
                .departmentName(a.getDoctor().getDepartment().getName())
                .scheduledAt(a.getScheduledAt())
                .durationMins(a.getDurationMins())
                .status(a.getStatus())
                .type(a.getType())
                .confirmationCode(a.getConfirmationCode())
                .reason(a.getReason())
                .notes(a.getNotes())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
