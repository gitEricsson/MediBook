package com.medibook.domain.analytics.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.analytics.dto.AppointmentAnalyticsResponse;
import com.medibook.domain.analytics.dto.DailyCapacityReportResponse;
import com.medibook.domain.analytics.dto.DoctorUtilizationResponse;
import com.medibook.domain.analytics.dto.RevenueAnalyticsResponse;
import com.medibook.domain.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin Analytics", description = "Platform analytics and reporting dashboard")
public class AdminAnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/appointments")
    @Operation(summary = "Appointment analytics by status, department, and type")
    public ApiResponse<AppointmentAnalyticsResponse> getAppointmentAnalytics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(analyticsService.getAppointmentAnalytics(from, to));
    }

    @GetMapping("/revenue")
    @Operation(summary = "Revenue summary and payment analytics")
    public ApiResponse<RevenueAnalyticsResponse> getRevenueAnalytics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(analyticsService.getRevenueAnalytics(from, to));
    }

    @GetMapping("/doctor-utilization")
    @Operation(summary = "Doctor utilization rates and appointment stats")
    public ApiResponse<DoctorUtilizationResponse> getDoctorUtilization(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(analyticsService.getDoctorUtilization(from, to));
    }

    @GetMapping("/capacity")
    @Operation(summary = "Daily capacity report: available slots, bookings, utilisation")
    public ApiResponse<DailyCapacityReportResponse> getDailyCapacityReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(analyticsService.getDailyCapacityReport(date));
    }
}
