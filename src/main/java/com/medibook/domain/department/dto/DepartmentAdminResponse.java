package com.medibook.domain.department.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentAdminResponse {
    private Long id;
    private String name;
    private String code;
    private Long doctorsCount;
    private Long apptCount90d;
    private boolean status;
    private int slotDurationMins;
    private int bufferMins;
    private BigDecimal baseConsultationFee;
}
