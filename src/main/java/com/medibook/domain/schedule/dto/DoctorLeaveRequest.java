package com.medibook.domain.schedule.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;

@Data
public class DoctorLeaveRequest {

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private String reason;

    @Pattern(regexp = "PERSONAL|SICK|CONFERENCE|HOLIDAY")
    private String leaveType = "PERSONAL";
}
