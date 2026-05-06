package com.medibook.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailVerifyRequest {

    @NotBlank(message = "Verification token is required")
    private String token;
}
