package com.medibook.domain.patient.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PatientProfileRequest {

    @Pattern(regexp = "^(A|B|AB|O)[+-]$", message = "Blood group must be A+, A-, B+, B-, AB+, AB-, O+ or O-")
    private String bloodGroup;

    @Size(max = 2000, message = "Allergies must be under 2000 characters")
    private String allergies;

    @Size(max = 5000, message = "Medical history must be under 5000 characters")
    private String medicalHistory;

    @Size(max = 255, message = "Emergency contact must be under 255 characters")
    private String emergencyContact;

    @Size(max = 20, message = "SSN must be under 20 characters")
    private String ssn;
}
