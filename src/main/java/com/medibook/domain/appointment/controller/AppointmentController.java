package com.medibook.domain.appointment.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.service.AppointmentService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Tag(name = "Appointments", description = "Appointment booking and management")
@SecurityRequirement(name = "bearerAuth")
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping
    @Operation(summary = "Book a new appointment (Patient)")
    public ResponseEntity<ApiResponse<AppointmentResponse>> book(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody AppointmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(appointmentService.book(principal.getId(), request)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get appointment by ID")
    public ResponseEntity<ApiResponse<AppointmentResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.getById(id)));
    }

    @GetMapping("/my")
    @Operation(summary = "List my appointments (Patient)")
    public ResponseEntity<ApiResponse<Page<AppointmentResponse>>> myAppointments(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                appointmentService.getByPatient(principal.getId(), pageable)));
    }

    @GetMapping("/doctor/{doctorId}")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "List appointments for a doctor (Doctor/Admin)")
    public ResponseEntity<ApiResponse<Page<AppointmentResponse>>> byDoctor(
            @PathVariable Long doctorId, @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                appointmentService.getByDoctor(doctorId, pageable)));
    }

    @PatchMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Confirm an appointment (Doctor/Admin)")
    public ResponseEntity<ApiResponse<AppointmentResponse>> confirm(
            @PathVariable Long id, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.confirm(id, principal.getId())));
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel an appointment")
    public ResponseEntity<ApiResponse<AppointmentResponse>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.cancel(id)));
    }
}
