package com.medibook.domain.patient.controller;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.response.ApiResponse;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.patient.dto.AccessGrantRequest;
import com.medibook.domain.patient.dto.AccessGrantResponse;
import com.medibook.domain.patient.service.AccessGrantService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Patient Access Grants", description = "Manage patient health record access for doctors")
@SecurityRequirement(name = "bearerAuth")
public class AccessGrantController {

    private final AccessGrantService accessGrantService;
    private final DoctorRepository   doctorRepository;

    // ── Patient-initiated grants ──────────────────────────────────────────────

    @PostMapping("/api/v1/patients/{patientId}/access-grants")
    @Operation(summary = "Patient directly grants doctor access to their records")
    public ResponseEntity<ApiResponse<AccessGrantResponse>> grantAccess(
            @PathVariable Long patientId,
            @Valid @RequestBody AccessGrantRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!principal.getId().equals(patientId)) {
            throw new MediBookException("Not authorized to grant access for this patient",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return ResponseEntity.ok(ApiResponse.ok(accessGrantService.grantAccess(patientId, request)));
    }

    @GetMapping("/api/v1/patients/{patientId}/access-grants")
    @Operation(summary = "List all access grants for a patient")
    public ResponseEntity<ApiResponse<Page<AccessGrantResponse>>> getGrants(
            @PathVariable Long patientId,
            Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!principal.getId().equals(patientId)) {
            throw new MediBookException("Not authorized to view grants for this patient",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return ResponseEntity.ok(ApiResponse.ok(accessGrantService.getPatientGrants(patientId, pageable)));
    }

    @GetMapping("/api/v1/patients/{patientId}/access-grants/{grantId}")
    @Operation(summary = "Get specific access grant details")
    public ResponseEntity<ApiResponse<AccessGrantResponse>> getGrant(
            @PathVariable Long patientId,
            @PathVariable Long grantId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!principal.getId().equals(patientId)) {
            throw new MediBookException("Not authorized to view this grant",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return ResponseEntity.ok(ApiResponse.ok(accessGrantService.getGrant(patientId, grantId)));
    }

    @DeleteMapping("/api/v1/patients/{patientId}/access-grants/{grantId}")
    @Operation(summary = "Revoke doctor access to patient records")
    public ResponseEntity<ApiResponse<String>> revokeAccess(
            @PathVariable Long patientId,
            @PathVariable Long grantId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!principal.getId().equals(patientId)) {
            throw new MediBookException("Not authorized to revoke access for this patient",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        accessGrantService.revokeAccess(patientId, grantId);
        return ResponseEntity.ok(ApiResponse.ok("Access revoked successfully"));
    }

    // ── Doctor-initiated request flow ────────────────────────────────────────

    @PostMapping("/api/v1/access-requests")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Doctor requests access to a patient's consultation history")
    public ResponseEntity<ApiResponse<AccessGrantResponse>> requestAccess(
            @Valid @RequestBody AccessRequestPayload body,
            @CurrentUser UserPrincipal principal) {
        Long doctorId = doctorRepository.findByUserId(principal.getId())
                .map(d -> d.getId())
                .orElseThrow(() -> new MediBookException("Doctor profile not found", HttpStatus.NOT_FOUND, "DOCTOR_NOT_FOUND"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(accessGrantService.requestAccess(doctorId, body.getPatientId(), body.getReason())));
    }

    @GetMapping("/api/v1/access-requests/incoming")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Patient sees pending access requests from doctors")
    public ResponseEntity<ApiResponse<Page<AccessGrantResponse>>> getIncomingRequests(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(accessGrantService.getIncomingRequests(principal.getId(), pageable)));
    }

    @GetMapping("/api/v1/access-requests/outgoing")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Doctor sees their outgoing access requests")
    public ResponseEntity<ApiResponse<Page<AccessGrantResponse>>> getOutgoingRequests(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        Long doctorId = doctorRepository.findByUserId(principal.getId())
                .map(d -> d.getId())
                .orElseThrow(() -> new MediBookException("Doctor profile not found", HttpStatus.NOT_FOUND, "DOCTOR_NOT_FOUND"));
        return ResponseEntity.ok(ApiResponse.ok(accessGrantService.getOutgoingRequests(doctorId, pageable)));
    }

    @PostMapping("/api/v1/access-requests/{grantId}/approve")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Patient approves a doctor's access request")
    public ResponseEntity<ApiResponse<AccessGrantResponse>> approveRequest(
            @PathVariable Long grantId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(accessGrantService.approveRequest(principal.getId(), grantId)));
    }

    @PostMapping("/api/v1/access-requests/{grantId}/deny")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Patient denies a doctor's access request")
    public ResponseEntity<ApiResponse<AccessGrantResponse>> denyRequest(
            @PathVariable Long grantId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(accessGrantService.denyRequest(principal.getId(), grantId)));
    }

    @Data
    static class AccessRequestPayload {
        private Long patientId;
        @Size(max = 500)
        private String reason;
    }
}
