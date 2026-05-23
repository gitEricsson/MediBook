package com.medibook.domain.patient.dto;

import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.patient.entity.PatientAccessGrant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@Builder
public class AccessGrantResponse {

    private Long id;
    private Long patientId;
    private Long doctorId;
    private String doctorName;
    private String doctorEmail;
    private String doctorDepartment;
    private String status;
    private LocalDateTime grantedAt;
    private LocalDateTime revokedAt;
    private String reason;
    /** Inclusive cutoff for which records this doctor may view. Null = no cutoff. */
    private LocalDate accessUpToDate;

    public static AccessGrantResponse fromEntity(PatientAccessGrant grant) {
        Doctor doctor = grant.getDoctor();
        return AccessGrantResponse.builder()
                .id(grant.getId())
                .patientId(grant.getPatient().getId())
                .doctorId(doctor.getId())
                .doctorName(doctor.getUser().getFirstName() + " " + doctor.getUser().getLastName())
                .doctorEmail(doctor.getUser().getEmail())
                .doctorDepartment(doctor.getDepartment() != null ? doctor.getDepartment().getName() : null)
                .status(grant.getStatus().name())
                .grantedAt(grant.getGrantedAt())
                .revokedAt(grant.getRevokedAt())
                .reason(grant.getReason())
                .accessUpToDate(grant.getAccessUpToDate())
                .build();
    }
}
