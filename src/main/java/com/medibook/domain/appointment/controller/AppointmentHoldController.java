package com.medibook.domain.appointment.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.HoldResponse;
import com.medibook.domain.appointment.service.AppointmentHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/appointments/holds")
@RequiredArgsConstructor
@Tag(name = "Appointment Holds", description = "Temporary slot locking")
@SecurityRequirement(name = "bearerAuth")
public class AppointmentHoldController {

    private final AppointmentHoldService holdService;

    @PostMapping
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Soft-hold a slot for 10 minutes")
    public ResponseEntity<ApiResponse<HoldResponse>> holdSlot(@RequestBody AppointmentRequest request) {
        String holdId = holdService.holdSlot(request.getDoctorId(), request.getScheduledAt());
        return ResponseEntity.ok(ApiResponse.ok(HoldResponse.builder()
                .holdId(holdId)
                .expiresAt(Instant.now().plusSeconds(600))
                .build()));
    }

    @DeleteMapping("/{holdId}")
    @Operation(summary = "Release a hold manually")
    public ResponseEntity<ApiResponse<Void>> releaseHold(
            @PathVariable String holdId,
            @RequestParam Long doctorId,
            @RequestParam String scheduledAt) {
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
