package com.medibook.domain.patient.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.patient.dto.PatientProfileRequest;
import com.medibook.domain.patient.dto.PatientProfileResponse;
import com.medibook.domain.patient.service.PatientProfileService;
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
@RequestMapping("/api/v1/me/profile")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PATIENT')")
@Tag(name = "Patient Profile", description = "Patient medical profile — PHI encrypted at rest")
@SecurityRequirement(name = "bearerAuth")
public class PatientProfileController {

    private final PatientProfileService patientProfileService;

    @GetMapping
    @Operation(summary = "Get own medical profile")
    public ResponseEntity<ApiResponse<PatientProfileResponse>> getProfile(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(patientProfileService.getByUserId(principal.getId())));
    }

    @PutMapping
    @Operation(summary = "Create or update own medical profile")
    public ResponseEntity<ApiResponse<PatientProfileResponse>> upsertProfile(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody PatientProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(patientProfileService.upsert(principal.getId(), request)));
    }
}
