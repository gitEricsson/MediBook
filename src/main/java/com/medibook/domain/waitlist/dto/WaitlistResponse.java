package com.medibook.domain.waitlist.dto;

import com.medibook.domain.waitlist.entity.WaitlistEntry;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class WaitlistResponse {

    private Long id;
    private Long patientId;
    private String patientName;
    private Long doctorId;
    private String doctorName;
    private Long departmentId;
    private String departmentName;
    private String specialization;
    private LocalDate preferredDate;
    private String status;
    private LocalDateTime promotedAt;
    private Long promotedAppointmentId;
    private LocalDateTime createdAt;

    public static WaitlistResponse fromEntity(WaitlistEntry e) {
        return WaitlistResponse.builder()
                .id(e.getId())
                .patientId(e.getPatient().getId())
                .patientName(e.getPatient().getFullName())
                .doctorId(e.getDoctor() != null ? e.getDoctor().getId() : null)
                .doctorName(e.getDoctor() != null ? e.getDoctor().getUser().getFullName() : null)
                .departmentId(e.getDepartment() != null ? e.getDepartment().getId() : null)
                .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                .specialization(e.getSpecialization())
                .preferredDate(e.getPreferredDate())
                .status(e.getStatus())
                .promotedAt(e.getPromotedAt())
                .promotedAppointmentId(e.getPromotedAppointment() != null ? e.getPromotedAppointment().getId() : null)
                .createdAt(e.getCreatedAt())
                .build();
    }
}
