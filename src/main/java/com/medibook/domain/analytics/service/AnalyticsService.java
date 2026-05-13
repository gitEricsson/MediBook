package com.medibook.domain.analytics.service;

import com.medibook.domain.analytics.dto.AppointmentAnalyticsResponse;
import com.medibook.domain.analytics.dto.DailyCapacityReportResponse;
import com.medibook.domain.analytics.dto.DailyCapacityReportResponse.DepartmentCapacitySummary;
import com.medibook.domain.analytics.dto.DoctorUtilizationResponse;
import com.medibook.domain.analytics.dto.RevenueAnalyticsResponse;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AppointmentRepository        appointmentRepository;
    private final DoctorRepository             doctorRepository;
    private final DoctorWorkingHoursRepository workingHoursRepository;
    private final PaymentRepository            paymentRepository;

    @Cacheable(value = "analytics", key = "'appointments-' + #from + '-' + #to")
    @Transactional(readOnly = true)
    public AppointmentAnalyticsResponse getAppointmentAnalytics(LocalDateTime from, LocalDateTime to) {
        List<Object[]> statusCounts = appointmentRepository.countByStatusBetween(from, to);
        List<Object[]> departmentCounts = appointmentRepository.countByDepartmentBetween(from, to);
        List<Object[]> typeCounts = appointmentRepository.countByTypeBetween(from, to);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0, pending = 0, completed = 0, cancelled = 0, noShow = 0;
        for (Object[] row : statusCounts) {
            String status = String.valueOf(row[0]);
            long count = ((Number) row[1]).longValue();
            byStatus.put(status, count);
            total += count;
            switch (status) {
                case "PENDING", "CONFIRMED" -> pending += count;
                case "COMPLETED" -> completed += count;
                case "CANCELLED" -> cancelled += count;
                case "NO_SHOW" -> noShow += count;
            }
        }

        Map<String, Long> byDept = departmentCounts.stream()
                .collect(Collectors.toMap(r -> String.valueOf(r[0]), r -> ((Number) r[1]).longValue(),
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> byType = typeCounts.stream()
                .collect(Collectors.toMap(r -> String.valueOf(r[0]), r -> ((Number) r[1]).longValue(),
                        (a, b) -> a, LinkedHashMap::new));

        double cancellationRate = total > 0 ? (cancelled * 100.0 / total) : 0;
        double noShowRate = total > 0 ? (noShow * 100.0 / total) : 0;

        return AppointmentAnalyticsResponse.builder()
                .totalAppointments(total)
                .pendingAppointments(pending)
                .completedAppointments(completed)
                .cancelledAppointments(cancelled)
                .noShowAppointments(noShow)
                .cancellationRatePercent(Math.round(cancellationRate * 100.0) / 100.0)
                .noShowRatePercent(Math.round(noShowRate * 100.0) / 100.0)
                .appointmentsByDepartment(byDept)
                .appointmentsByStatus(byStatus)
                .appointmentsByType(byType)
                .dailyCounts(List.of())
                .build();
    }

    @Cacheable(value = "analytics", key = "'revenue-' + #from + '-' + #to")
    @Transactional(readOnly = true)
    public RevenueAnalyticsResponse getRevenueAnalytics(LocalDateTime from, LocalDateTime to) {
        List<Object[]> paymentStats = paymentRepository.getPaymentStats(from, to);

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalRefunds = BigDecimal.ZERO;
        long totalPayments = 0, successful = 0, failed = 0, refunded = 0;

        for (Object[] row : paymentStats) {
            String status = String.valueOf(row[0]);
            long count = ((Number) row[1]).longValue();
            BigDecimal amount = row[2] != null ? new BigDecimal(String.valueOf(row[2])) : BigDecimal.ZERO;
            BigDecimal refundAmt = row[3] != null ? new BigDecimal(String.valueOf(row[3])) : BigDecimal.ZERO;

            totalPayments += count;
            switch (status) {
                case "SUCCESSFUL" -> { successful = count; totalRevenue = totalRevenue.add(amount); }
                case "FAILED"     -> failed = count;
                case "REFUNDED"   -> { refunded = count; totalRefunds = totalRefunds.add(refundAmt); }
            }
        }

        return RevenueAnalyticsResponse.builder()
                .totalRevenue(totalRevenue)
                .totalRefunds(totalRefunds)
                .netRevenue(totalRevenue.subtract(totalRefunds))
                .totalPayments(totalPayments)
                .successfulPayments(successful)
                .failedPayments(failed)
                .refundedPayments(refunded)
                .revenueByDepartment(Map.of())
                .revenueByMonth(Map.of())
                .build();
    }

    @Transactional(readOnly = true)
    public DailyCapacityReportResponse getDailyCapacityReport(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay   = date.atTime(23, 59, 59);

        // Day of week: Java DayOfWeek.getValue() returns 1=Mon..7=Sun — matches doctor_working_hours schema
        int dow = date.getDayOfWeek().getValue();
        List<DoctorWorkingHours> workingHours = workingHoursRepository.findByDayOfWeekForActiveDoctors(dow);

        int totalActiveDoctors = (int) workingHours.stream()
                .map(wh -> wh.getDoctor().getId())
                .distinct()
                .count();

        int totalAvailableSlots = workingHours.stream()
                .mapToInt(wh -> {
                    long minutes = Duration.between(wh.getStartTime(), wh.getEndTime()).toMinutes();
                    int slotDuration = wh.getDoctor().getSlotDurationMins();
                    return slotDuration > 0 ? (int) (minutes / slotDuration) : 0;
                })
                .sum();

        List<Object[]> statusRows = appointmentRepository.countByStatusBetween(startOfDay, endOfDay);
        long booked = 0, completed = 0, cancelled = 0, noShow = 0;
        for (Object[] row : statusRows) {
            String status = String.valueOf(row[0]);
            long count   = ((Number) row[1]).longValue();
            switch (status) {
                case "PENDING", "CONFIRMED", "ON_HOLD" -> booked    += count;
                case "COMPLETED"                        -> completed += count;
                case "CANCELLED"                        -> cancelled += count;
                case "NO_SHOW"                          -> noShow    += count;
            }
        }

        double utilization = totalAvailableSlots > 0
                ? Math.round((booked + completed) * 100.0 / totalAvailableSlots * 100.0) / 100.0
                : 0.0;

        List<DepartmentCapacitySummary> deptSummaries =
                appointmentRepository.getDepartmentCapacityStats(startOfDay, endOfDay).stream()
                        .map(row -> DepartmentCapacitySummary.builder()
                                .departmentId(((Number) row[0]).longValue())
                                .departmentName(String.valueOf(row[1]))
                                .totalAppointments(((Number) row[2]).longValue())
                                .completedAppointments(((Number) row[3]).longValue())
                                .cancelledAppointments(((Number) row[4]).longValue())
                                .build())
                        .toList();

        return DailyCapacityReportResponse.builder()
                .date(date)
                .totalActiveDoctors(totalActiveDoctors)
                .totalAvailableSlots(totalAvailableSlots)
                .bookedSlots(booked)
                .completedAppointments(completed)
                .cancelledAppointments(cancelled)
                .noShowAppointments(noShow)
                .utilizationPercent(utilization)
                .byDepartment(deptSummaries)
                .build();
    }

    @Transactional(readOnly = true)
    public DoctorUtilizationResponse getDoctorUtilization(LocalDateTime from, LocalDateTime to) {
        List<Object[]> stats = appointmentRepository.getDoctorUtilizationStats(from, to);

        List<DoctorUtilizationResponse.DoctorStat> doctorStats = stats.stream().map(row -> {
            long total = ((Number) row[2]).longValue();
            long completed = ((Number) row[3]).longValue();
            long cancelled = ((Number) row[4]).longValue();
            double util = total > 0 ? (completed * 100.0 / total) : 0;
            return DoctorUtilizationResponse.DoctorStat.builder()
                    .doctorId(((Number) row[0]).longValue())
                    .doctorName(String.valueOf(row[1]))
                    .totalAppointments(total)
                    .completedAppointments(completed)
                    .cancelledAppointments(cancelled)
                    .utilizationPercent(Math.round(util * 100.0) / 100.0)
                    .averageRating(row[5] != null ? ((Number) row[5]).doubleValue() : 0.0)
                    .build();
        }).toList();

        return DoctorUtilizationResponse.builder().doctors(doctorStats).build();
    }
}
