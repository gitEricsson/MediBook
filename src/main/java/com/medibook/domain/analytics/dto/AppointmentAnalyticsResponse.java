package com.medibook.domain.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentAnalyticsResponse {

    private long totalAppointments;
    private long pendingAppointments;
    private long completedAppointments;
    private long cancelledAppointments;
    private long noShowAppointments;
    private double cancellationRatePercent;
    private double noShowRatePercent;
    private Map<String, Long> appointmentsByDepartment;
    private Map<String, Long> appointmentsByStatus;
    private Map<String, Long> appointmentsByType;
    private List<DailyCount> dailyCounts;

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class DailyCount {
        private String date;
        private long count;
    }
}
