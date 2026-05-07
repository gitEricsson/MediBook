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
    @Operation(summary = "Get full appointment detail")
    public ResponseEntity<ApiResponse<AppointmentResponse>> getAppointmentDetail(
            @PathVariable Long id, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.getById(id)));
    }

    @GetMapping("/patients/{patientId}/summary")
    @Operation(summary = "Get patient history summary")
    public ResponseEntity<ApiResponse<PatientSummaryResponse>> getPatientSummary(
            @PathVariable Long patientId) {
        return ResponseEntity.ok(ApiResponse.ok(historyService.getPatientSummary(patientId)));
    }

    @PostMapping("/appointments/{id}/transition")
    @Operation(summary = "Transition appointment status (Complete/Cancel/NoShow)")
    public ResponseEntity<ApiResponse<AppointmentResponse>> transition(
            @PathVariable Long id, 
            @Valid @RequestBody TransitionRequest request,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(transitionService.transition(id, request, principal.getId())));
    }

    @PostMapping("/appointments/{id}/call")
    @Operation(summary = "Get click-to-call URI")
    public ResponseEntity<ApiResponse<String>> callPatient(@PathVariable Long id) {
        AppointmentResponse appt = appointmentService.getById(id);
        String phone = appointmentService.getPatientPhone(appt.getPatientId());
        if (phone == null || phone.isBlank()) {
            throw new com.medibook.common.exception.MediBookException(
                    "Patient has no phone number on file", org.springframework.http.HttpStatus.NOT_FOUND, "PHONE_NOT_FOUND");
        }
        return ResponseEntity.ok(ApiResponse.ok("tel:" + phone));
    }
}
