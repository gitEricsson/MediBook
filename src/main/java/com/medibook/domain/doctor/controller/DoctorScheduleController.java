package com.medibook.domain.doctor.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.doctor.dto.ScheduleDayResponse;
import com.medibook.domain.doctor.dto.ScheduleSummaryResponse;
import com.medibook.domain.doctor.service.DoctorScheduleService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/me/schedule")
@RequiredArgsConstructor
@Tag(name = "Doctor Schedule", description = "Doctor daily and weekly schedule views")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('DOCTOR')")
public class DoctorScheduleController {

    private final DoctorScheduleService scheduleService;

    @GetMapping
    @Operation(summary = "Get daily schedule")
    public ResponseEntity<ApiResponse<ScheduleDayResponse>> getDailySchedule(
            @CurrentUser UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        // Assuming doctor ID is the same as User ID in our current simplified schema (or we fetch it inside service)
        // Let's assume principal.getId() is used to find Doctor entity inside service. 
        // For simplicity, we'll pass principal.getId() representing the doctorId for now.
        return ResponseEntity.ok(ApiResponse.ok(scheduleService.getDailySchedule(principal.getId(), date)));
    }

    @GetMapping("/week")
    @Operation(summary = "Get weekly schedule counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getWeeklySummary(
            @CurrentUser UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekOf) {
        return ResponseEntity.ok(ApiResponse.ok(scheduleService.getWeeklySummary(principal.getId(), weekOf)));
    }

    @GetMapping("/summary")
    @Operation(summary = "Get today's schedule summary")
    public ResponseEntity<ApiResponse<ScheduleSummaryResponse>> getTodaySummary(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(scheduleService.getScheduleSummary(principal.getId(), LocalDate.now())));
    }

    @GetMapping("/up-next")
    @Operation(summary = "Get next upcoming appointment")
    public ResponseEntity<ApiResponse<AppointmentResponse>> getUpNext(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(scheduleService.getUpNext(principal.getId())));
    }
}
