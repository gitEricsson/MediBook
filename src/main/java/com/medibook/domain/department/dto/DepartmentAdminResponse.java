package com.medibook.domain.department.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
