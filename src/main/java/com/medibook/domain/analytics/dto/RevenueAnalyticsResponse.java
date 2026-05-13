package com.medibook.domain.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueAnalyticsResponse {

    private BigDecimal totalRevenue;
    private BigDecimal totalRefunds;
    private BigDecimal netRevenue;
    private long totalPayments;
    private long successfulPayments;
    private long failedPayments;
    private long refundedPayments;
    private Map<String, BigDecimal> revenueByDepartment;
    private Map<String, BigDecimal> revenueByMonth;
}
