package com.medibook.domain.waitlist.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class WaitlistRequest {

    private Long doctorId;
    private Long departmentId;
    private String specialization;
    private LocalDate preferredDate;
}
