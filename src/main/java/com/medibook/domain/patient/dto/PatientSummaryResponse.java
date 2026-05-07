package com.medibook.domain.patient.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class PatientSummaryResponse {
    private Long patientId;
    private String fullName;
    private LocalDate dateOfBirth;
    private String bloodGroup;
    private String allergies;
    private String medicalHistory;
    private String lastVisitDate;
    private String lastVisitDiagnosis;
}
