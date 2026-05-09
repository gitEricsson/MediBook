package com.medibook.domain.doctor.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.doctor.dto.ScheduleDayResponse;
import com.medibook.domain.doctor.dto.ScheduleSummaryResponse;
import com.medibook.domain.doctor.dto.WorkingHoursRequest;
import com.medibook.domain.doctor.dto.WorkingHoursResponse;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.service.DoctorScheduleService;
import com.medibook.domain.doctor.service.DoctorWorkingHoursService;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/me/schedule")
@RequiredArgsConstructor
@Tag(name = "Doctor Schedule", description = "Doctor daily and weekly schedule views")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('DOCTOR')")
public class DoctorScheduleController {

    private final DoctorScheduleService       scheduleService;
    private final DoctorWorkingHoursService   workingHoursService;
    private final DoctorRepository            doctorRepository;

    @GetMapping
    @Operation(summary = "Get daily schedule")
    public ResponseEntity<ApiResponse<ScheduleDayResponse>> getDailySchedule(
            @CurrentUser UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(scheduleService.getDailySchedule(resolveOwnDoctorId(principal), date)));
    }

    @GetMapping("/week")
    @Operation(summary = "Get weekly schedule counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getWeeklySummary(
            @CurrentUser UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekOf) {
        return ResponseEntity.ok(ApiResponse.ok(scheduleService.getWeeklySummary(resolveOwnDoctorId(principal), weekOf)));
    }

    @GetMapping("/summary")
    @Operation(summary = "Get today's schedule summary")
    public ResponseEntity<ApiResponse<ScheduleSummaryResponse>> getTodaySummary(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(scheduleService.getScheduleSummary(resolveOwnDoctorId(principal), LocalDate.now())));
    }

    @GetMapping("/up-next")
    @Operation(summary = "Get next upcoming appointment")
    public ResponseEntity<ApiResponse<AppointmentResponse>> getUpNext(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(scheduleService.getUpNext(resolveOwnDoctorId(principal))));
    }

    @GetMapping("/hours")
    @Operation(summary = "Get own working hours")
    public ResponseEntity<ApiResponse<List<WorkingHoursResponse>>> getMyHours(
            @CurrentUser UserPrincipal principal) {
        Long doctorId = resolveOwnDoctorId(principal);
        return ResponseEntity.ok(ApiResponse.ok(workingHoursService.getByDoctorId(doctorId)));
    }

    @PutMapping("/hours")
    @Operation(summary = "Set own working hours (replaces all)")
    public ResponseEntity<ApiResponse<List<WorkingHoursResponse>>> setMyHours(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody WorkingHoursRequest request) {
        Long doctorId = resolveOwnDoctorId(principal);
        return ResponseEntity.ok(ApiResponse.ok(workingHoursService.replaceAll(doctorId, request)));
    }

    private Long resolveOwnDoctorId(UserPrincipal principal) {
        return doctorRepository.findByUserId(principal.getId())
                .map(d -> d.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "userId", principal.getId()));
    }
}
