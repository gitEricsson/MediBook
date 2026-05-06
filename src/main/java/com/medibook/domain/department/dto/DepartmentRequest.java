package com.medibook.domain.department.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DepartmentRequest {

    @NotBlank(message = "Department name is required")
    @Size(max = 150)
    private String name;

    @NotBlank(message = "Department code is required")
    @Size(max = 50)
    private String code;

    @Size(max = 2000)
    private String description;
}
