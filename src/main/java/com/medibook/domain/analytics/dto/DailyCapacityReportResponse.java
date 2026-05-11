package com.medibook.domain.analytics.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class DailyCapacityReportResponse {

    private LocalDate date;
    private int totalActiveDoctors;
    private int totalAvailableSlots;
    private long bookedSlots;
    private long completedAppointments;
    private long cancelledAppointments;
    private long noShowAppointments;
    private double utilizationPercent;
    private List<DepartmentCapacitySummary> byDepartment;

    @Data
    @Builder
    public static class DepartmentCapacitySummary {
        private Long   departmentId;
        private String departmentName;
        private long   totalAppointments;
        private long   completedAppointments;
        private long   cancelledAppointments;
    }
}
