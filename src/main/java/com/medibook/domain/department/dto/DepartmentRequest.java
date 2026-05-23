package com.medibook.domain.department.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

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

    /** Average consultation duration for this department (minutes). Defaults to 30. */
    @Min(value = 5, message = "Slot duration must be at least 5 minutes")
    private int slotDurationMins = 30;

    /** Cleanup / prep buffer between consecutive slots (minutes). Defaults to 0. */
    @Min(value = 0, message = "Buffer cannot be negative")
    private int bufferMins = 0;

    /** Base consultation fee for this department (NGN). Defaults to 5000. */
    @DecimalMin(value = "0.00", message = "Base fee cannot be negative")
    private BigDecimal baseConsultationFee = BigDecimal.valueOf(5000.00);
}
