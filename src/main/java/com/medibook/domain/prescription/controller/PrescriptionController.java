package com.medibook.domain.prescription.controller;

import com.medibook.domain.prescription.dto.PrescriptionDtos.*;
import com.medibook.domain.prescription.entity.PrescriptionStatus;
import com.medibook.domain.prescription.service.PrescriptionService;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/prescriptions")
@RequiredArgsConstructor
@Tag(name = "Prescriptions", description = "Structured prescription lines per appointment")
public class PrescriptionController {

    private final PrescriptionService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Issue a prescription line on an appointment (Doctor only)")
    public ResponseEntity<Response> create(
            @Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.create(request, principal));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Edit an active prescription line")
    public ResponseEntity<Response> update(
            @PathVariable Long id,
            @RequestBody UpdateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.update(id, request, principal));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Cancel a prescription line")
    public ResponseEntity<Response> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) CancelRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.cancel(id, request, principal));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single prescription (doctor/patient/admin on the appointment)")
    public ResponseEntity<Response> getById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.getById(id, principal));
    }

    @GetMapping("/appointment/{appointmentId}")
    @Operation(summary = "List all prescriptions issued on a given appointment")
    public ResponseEntity<List<Response>> listForAppointment(
            @PathVariable Long appointmentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.listForAppointment(appointmentId, principal));
    }

    @GetMapping("/patient/{patientId}")
    @Operation(summary = "Paginated prescription history for a patient")
    public ResponseEntity<Page<Response>> listForPatient(
            @PathVariable Long patientId,
            @RequestParam(required = false) PrescriptionStatus status,
            Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.listForPatient(patientId, status, pageable, principal));
    }
}
