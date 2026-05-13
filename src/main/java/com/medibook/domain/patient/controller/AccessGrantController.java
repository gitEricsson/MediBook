package com.medibook.domain.patient.controller;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.response.ApiResponse;
import com.medibook.domain.patient.dto.AccessGrantRequest;
import com.medibook.domain.patient.dto.AccessGrantResponse;
import com.medibook.domain.patient.service.AccessGrantService;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/patients/{patientId}/access-grants")
@RequiredArgsConstructor
@Tag(name = "Patient Access Grants", description = "Manage patient health record access for doctors")
public class AccessGrantController {

    private final AccessGrantService accessGrantService;

    @PostMapping
    @Operation(summary = "Grant doctor access to patient health records")
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

    @GetMapping
    @Operation(summary = "List doctors with access to patient records")
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

    @GetMapping("/{grantId}")
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

    @DeleteMapping("/{grantId}")
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
}
