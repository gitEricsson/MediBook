package com.medibook.domain.appointment.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.HoldResponse;
import com.medibook.domain.appointment.service.AppointmentHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/appointments/holds")
@RequiredArgsConstructor
@Tag(name = "Appointment Holds", description = "Temporary slot locking")
@SecurityRequirement(name = "bearerAuth")
public class AppointmentHoldController {

    private final AppointmentHoldService holdService;

    @PostMapping
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "Soft-hold a slot",
        description = "Temporarily reserves a doctor appointment slot for 10 minutes to allow patient confirmation.",
        tags = {"Appointment Holds"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Slot held successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="400", description = "Invalid hold request")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - patient role required")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="409", description = "Conflict - slot not available")
    public ResponseEntity<ApiResponse<HoldResponse>> holdSlot(@Valid @RequestBody AppointmentRequest request) {
        String holdId = holdService.holdSlot(request.getDoctorId(), request.getScheduledAt());
        return ResponseEntity.ok(ApiResponse.ok(HoldResponse.builder()
                .holdId(holdId)
                .expiresAt(Instant.now().plusSeconds(600))
                .build()));
    }

    @DeleteMapping("/{holdId}")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "Release a hold",
        description = "Manually releases a hold before expiration, freeing the slot for other patients.",
        tags = {"Appointment Holds"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Hold released successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - patient role required")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Hold not found")
    public ResponseEntity<ApiResponse<Void>> releaseHold(
            @PathVariable String holdId,
            @RequestParam Long doctorId,
            @RequestParam String scheduledAt) {
        holdService.releaseHold(doctorId, LocalDateTime.parse(scheduledAt), holdId);
        return ResponseEntity.ok(ApiResponse.noContent("Hold released"));
    }
}
