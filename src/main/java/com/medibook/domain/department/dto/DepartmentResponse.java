package com.medibook.domain.department.dto;

import com.medibook.domain.department.entity.Department;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DepartmentResponse {

    private Long id;
    private String name;
    private String code;
    private String description;
    private boolean isActive;
    private LocalDateTime createdAt;

    public static DepartmentResponse fromEntity(Department d) {
        return DepartmentResponse.builder()
                .id(d.getId())
                .name(d.getName())
                .code(d.getCode())
                .description(d.getDescription())
                .isActive(d.isActive())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
