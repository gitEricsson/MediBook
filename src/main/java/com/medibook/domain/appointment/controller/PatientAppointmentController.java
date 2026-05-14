package com.medibook.domain.appointment.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.common.response.CursorPageResponse;
import com.medibook.domain.appointment.dto.*;
import com.medibook.domain.appointment.service.AppointmentIdempotencyService;
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
import org.springframework.security.access.AccessDeniedException;
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
    private final AppointmentIdempotencyService appointmentIdempotencyService;

    @PostMapping("/appointments")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "Book a new appointment",
        description = "Creates a new appointment request with a doctor. Supports idempotency via Idempotency-Key header.",
        tags = {"Patient Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="201", description = "Appointment successfully created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="400", description = "Invalid appointment request")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized - authentication required")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - patient role required")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="409", description = "Conflict - slot not available")
    public ResponseEntity<ApiResponse<AppointmentResponse>> book(
            @CurrentUser UserPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AppointmentRequest request) {
        AppointmentResponse response = appointmentIdempotencyService.execute(
                principal.getId(),
                idempotencyKey,
                request,
                () -> appointmentService.book(principal.getId(), request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(response));
    }

    @RequestMapping(path = {"/appointments/{id}/ics", "/appointments/{id}/calendar.ics"}, method = {RequestMethod.GET, RequestMethod.POST})
    @PreAuthorize("isAuthenticated()")
    @Operation(
        summary = "Download ICS calendar file",
        description = "Exports appointment details as an iCalendar file for calendar applications.",
        tags = {"Patient Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "ICS file generated successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - not authorized to access this appointment")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Appointment not found")
    public ResponseEntity<byte[]> getCalendarIcs(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        AppointmentResponse appointment = appointmentService.getById(id);

        // Verify caller is either the patient, the doctor, or an admin
        boolean isPatient = appointment.getPatientId().equals(principal.getId());
        boolean isDoctor = appointment.getDoctorId().equals(principal.getId());
        boolean isAdmin = principal.hasRole("ROLE_ADMIN") || principal.hasRole("ROLE_SUPER_ADMIN");

        if (!isPatient && !isDoctor && !isAdmin) {
            throw new AccessDeniedException("Not authorized to access this appointment");
        }

        String ics = appointmentService.generateIcs(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/calendar; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"appointment-" + id + ".ics\"");
        return new ResponseEntity<>(ics.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    @GetMapping("/me/appointments")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "List my appointments",
        description = "Retrieves paginated list of appointments for authenticated patient (upcoming or past).",
        tags = {"Patient Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Appointments retrieved successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden")
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

    @GetMapping("/me/appointments/cursor")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "List my appointments (cursor pagination)",
        description = "Retrieves appointments using cursor-based pagination for efficient large dataset handling.",
        tags = {"Patient Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Appointments retrieved successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden")
    public ResponseEntity<ApiResponse<CursorPageResponse<AppointmentResponse>>> myAppointmentsCursor(
            @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = "upcoming") String tab,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                appointmentService.getByPatientCursor(principal.getId(), tab, cursor, limit)));
    }

    @GetMapping("/me/appointments/{id}")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "Get appointment detail",
        description = "Retrieves complete details for a specific appointment owned by the authenticated patient.",
        tags = {"Patient Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Appointment details retrieved successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - not authorized to view this appointment")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Appointment not found")
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
    @Operation(
        summary = "Cancel an appointment",
        description = "Cancels an appointment with optional cancellation reason. Subject to cancellation policy constraints.",
        tags = {"Patient Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Appointment cancelled successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="400", description = "Invalid cancellation request")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - cannot cancel appointment")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Appointment not found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="409", description = "Conflict - appointment cannot be cancelled (e.g., already completed)")
    public ResponseEntity<ApiResponse<AppointmentResponse>> cancel(
            @PathVariable Long id,
            @Valid @RequestBody CancelRequest request,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.cancel(id, request, principal)));
    }

    @PostMapping("/appointments/{id}/reschedule")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "Reschedule an appointment",
        description = "Changes appointment date/time to a new available slot. Subject to rescheduling policies.",
        tags = {"Patient Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Appointment rescheduled successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="400", description = "Invalid reschedule request")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - cannot reschedule appointment")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Appointment not found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="409", description = "Conflict - new slot not available")
    public ResponseEntity<ApiResponse<AppointmentResponse>> reschedule(
            @PathVariable Long id, 
            @Valid @RequestBody RescheduleRequest request,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.reschedule(id, request, principal)));
    }

    @GetMapping("/policies/cancellation")
    @Operation(
        summary = "Get cancellation policy",
        description = "Retrieves the platform's appointment cancellation policy and fee schedule.",
        tags = {"Patient Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Cancellation policy retrieved successfully")
    public ResponseEntity<ApiResponse<CancellationPolicyResponse>> getCancellationPolicy() {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.getCancellationPolicy()));
    }
}
