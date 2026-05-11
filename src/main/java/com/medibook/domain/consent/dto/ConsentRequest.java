package com.medibook.domain.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ConsentRequest {

    @NotBlank
    @Pattern(regexp = "TELEMEDICINE|DATA_PROCESSING|MARKETING|PHI_SHARING",
            message = "Consent type must be one of: TELEMEDICINE, DATA_PROCESSING, MARKETING, PHI_SHARING")
    private String consentType;

    private boolean granted;
}
