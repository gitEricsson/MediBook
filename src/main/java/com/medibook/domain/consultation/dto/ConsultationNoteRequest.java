package com.medibook.domain.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ConsultationNoteRequest {

    @NotBlank(message = "Diagnosis is required")
    private String diagnosis;

    @NotBlank(message = "Treatment plan is required")
    private String treatmentPlan;

    private String prescriptions;

    private LocalDate followUpDate;
}
