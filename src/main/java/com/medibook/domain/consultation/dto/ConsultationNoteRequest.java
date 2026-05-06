package com.medibook.domain.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ConsultationNoteRequest {

    @NotBlank(message = "Diagnosis is required")
    @Size(max = 5000, message = "Diagnosis must not exceed 5000 characters")
    private String diagnosis;

    @NotBlank(message = "Treatment plan is required")
    @Size(max = 5000, message = "Treatment plan must not exceed 5000 characters")
    private String treatmentPlan;

    @Size(max = 5000, message = "Prescriptions must not exceed 5000 characters")
    private String prescriptions;

    private LocalDate followUpDate;
}
