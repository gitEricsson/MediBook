package com.medibook.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetAdminPasswordRequest {

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 128, message = "Password must be 8–128 characters")
    private String newPassword;
}
