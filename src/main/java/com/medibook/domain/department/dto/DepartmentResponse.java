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
    private String description;
    private LocalDateTime createdAt;

    public static DepartmentResponse fromEntity(Department d) {
        return DepartmentResponse.builder()
                .id(d.getId())
                .name(d.getName())
                .description(d.getDescription())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
