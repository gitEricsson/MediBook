package com.medibook.domain.appointment.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.dto.TransitionRequest;
import com.medibook.domain.appointment.service.AppointmentService;
import com.medibook.domain.appointment.service.AppointmentTransitionService;
import com.medibook.domain.patient.dto.PatientSummaryResponse;
import com.medibook.domain.patient.service.PatientHistoryService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Doctor Appointments", description = "Doctor appointment transitions and details")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('DOCTOR')")
public class DoctorAppointmentController {

    private final AppointmentService appointmentService;
    private final AppointmentTransitionService transitionService;
    private final PatientHistoryService historyService;

    @GetMapping("/appointments/{id}")
    @Operation(
        summary = "Get appointment detail",
        description = "Retrieves full appointment details including patient information and consultation notes.",
        tags = {"Doctor Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Appointment details retrieved successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - doctor role required")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Appointment not found")
    public ResponseEntity<ApiResponse<AppointmentResponse>> getAppointmentDetail(
            @PathVariable Long id, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.getByIdForDoctor(id, principal)));
    }

    @GetMapping("/patients/{patientId}/summary")
    @Operation(
        summary = "Get patient history summary",
        description = "Retrieves a summary of patient medical history, past diagnoses, and previous appointment records.",
        tags = {"Doctor Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Patient summary retrieved successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - doctor role required or no access to patient")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Patient not found")
    public ResponseEntity<ApiResponse<PatientSummaryResponse>> getPatientSummary(
            @PathVariable Long patientId,
            @CurrentUser UserPrincipal principal) {
        appointmentService.ensureDoctorCanAccessPatient(principal.getId(), patientId);
        return ResponseEntity.ok(ApiResponse.ok(historyService.getPatientSummary(patientId)));
    }

    @PostMapping("/appointments/{id}/transition")
    @Operation(
        summary = "Transition appointment status",
        description = "Changes appointment state (Complete/Cancel/NoShow) and updates related records and notifications.",
        tags = {"Doctor Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Appointment status changed successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="400", description = "Invalid transition request")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - doctor role required")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Appointment not found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="409", description = "Conflict - invalid status transition")
    public ResponseEntity<ApiResponse<AppointmentResponse>> transition(
            @PathVariable Long id, 
            @Valid @RequestBody TransitionRequest request,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(transitionService.transition(id, request, principal.getId())));
    }

    @PostMapping("/appointments/{id}/call")
    @Operation(
        summary = "Get click-to-call URI",
        description = "Returns a tel: URI for initiating a direct call to the patient.",
        tags = {"Doctor Appointments"}
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="200", description = "Click-to-call URI generated successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="401", description = "Unauthorized")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="403", description = "Forbidden - doctor role required")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode ="404", description = "Appointment not found or patient phone not available")
    public ResponseEntity<ApiResponse<String>> callPatient(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        String phone = appointmentService.getPatientPhoneForDoctor(id, principal);
        if (phone == null || phone.isBlank()) {
            throw new com.medibook.common.exception.MediBookException(
                    "Patient has no phone number on file", org.springframework.http.HttpStatus.NOT_FOUND, "PHONE_NOT_FOUND");
        }
        return ResponseEntity.ok(ApiResponse.ok("tel:" + phone));
    }
}
