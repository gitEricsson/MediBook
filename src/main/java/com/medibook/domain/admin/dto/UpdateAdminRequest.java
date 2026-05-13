package com.medibook.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateAdminRequest {

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    private String lastName;

    @Size(max = 20)
    @Pattern(regexp = "^\\+?[0-9 \\-().]{0,20}$",
             message = "Invalid phone number format",
             flags = Pattern.Flag.CASE_INSENSITIVE)
    private String phone;
}
