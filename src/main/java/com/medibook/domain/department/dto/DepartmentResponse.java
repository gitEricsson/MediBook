package com.medibook.domain.department.dto;

import com.medibook.domain.department.entity.Department;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentResponse {

    private Long id;
    private String name;
    private String code;
    private String description;
    private boolean isActive;
    private int slotDurationMins;
    private int bufferMins;
    private BigDecimal baseConsultationFee;
    private LocalDateTime createdAt;

    public static DepartmentResponse fromEntity(Department d) {
        return DepartmentResponse.builder()
                .id(d.getId())
                .name(d.getName())
                .code(d.getCode())
                .description(d.getDescription())
                .isActive(d.isActive())
                .slotDurationMins(d.getSlotDurationMins())
                .bufferMins(d.getBufferMins())
                .baseConsultationFee(d.getBaseConsultationFee())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
