package com.medibook.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateDraftRequest(
        @NotBlank(message = "patientMessage is required") String patientMessage) {}
