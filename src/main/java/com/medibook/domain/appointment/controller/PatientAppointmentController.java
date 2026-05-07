package com.medibook.domain.appointment.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.*;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Patient Appointments", description = "Patient booking and appointment management")
@SecurityRequirement(name = "bearerAuth")
public class PatientAppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping("/appointments")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Book a new appointment")
    public ResponseEntity<ApiResponse<AppointmentResponse>> book(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody AppointmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(appointmentService.book(principal.getId(), request)));
    }

    @PostMapping("/appointments/{id}/calendar.ics")
    @Operation(summary = "Get ICS calendar file for appointment")
    public ResponseEntity<byte[]> getCalendarIcs(@PathVariable Long id) {
        String ics = appointmentService.generateIcs(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/calendar"));
        headers.setContentDispositionFormData("attachment", "appointment.ics");
        return new ResponseEntity<>(ics.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    @GetMapping("/me/appointments")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "List my appointments")
    public ResponseEntity<ApiResponse<Page<AppointmentResponse>>> myAppointments(
            @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = "upcoming") String tab,
            @PageableDefault(size = 20) Pageable pageable) {
        
        Page<AppointmentResponse> page;
        if ("past".equalsIgnoreCase(tab)) {
            page = appointmentService.getPastByPatient(principal.getId(), pageable);
        } else {
            page = appointmentService.getUpcomingByPatient(principal.getId(), pageable);
        }
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/me/appointments/{id}")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Get full visit detail")
    public ResponseEntity<ApiResponse<AppointmentResponse>> getMyAppointmentDetail(
            @PathVariable Long id, @CurrentUser UserPrincipal principal) {
        AppointmentResponse appt = appointmentService.getById(id);
        if (!appt.getPatientId().equals(principal.getId())) {
            throw new com.medibook.common.exception.MediBookException(
                    "Not authorized to view this appointment", org.springframework.http.HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return ResponseEntity.ok(ApiResponse.ok(appt));
    }

    @PostMapping("/appointments/{id}/cancel")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Cancel an appointment")
    public ResponseEntity<ApiResponse<AppointmentResponse>> cancel(
            @PathVariable Long id, 
            @RequestBody CancelRequest request,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.cancel(id, request, principal)));
    }

    @PostMapping("/appointments/{id}/reschedule")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Reschedule an appointment")
    public ResponseEntity<ApiResponse<AppointmentResponse>> reschedule(
            @PathVariable Long id, 
            @Valid @RequestBody RescheduleRequest request,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.reschedule(id, request, principal)));
    }

    @GetMapping("/policies/cancellation")
    @Operation(summary = "Get cancellation policy details")
    public ResponseEntity<ApiResponse<CancellationPolicyResponse>> getCancellationPolicy() {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.getCancellationPolicy()));
    }
}
