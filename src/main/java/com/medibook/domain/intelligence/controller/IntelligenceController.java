package com.medibook.domain.intelligence.controller;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.response.ApiResponse;
import com.medibook.domain.intelligence.service.NoShowPredictionService;
import com.medibook.domain.intelligence.service.SymptomTriageService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/intelligence")
@RequiredArgsConstructor
@Tag(name = "Intelligence", description = "AI-assisted features (rules-based + stub implementations)")
public class IntelligenceController {

    private final NoShowPredictionService predictionService;
    private final SymptomTriageService    triageService;

    @GetMapping("/no-show-risk")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Predict no-show risk for a patient appointment. For scheduling optimization only, NOT clinical.")
    public ApiResponse<NoShowPredictionService.NoShowRisk> predictNoShowRisk(
            @RequestParam Long patientId,
            @RequestParam Long doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime scheduledAt,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(predictionService.predict(patientId, doctorId, scheduledAt));
    }

    @PostMapping("/symptom-triage")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'ADMIN')")
    @Operation(summary = "AI-assisted symptom triage. OUTPUT IS NOT A DIAGNOSIS. Doctor review mandatory for any clinical use.")
    public ApiResponse<SymptomTriageService.TriageResult> triageSymptoms(
            @RequestParam Long patientId,
            @RequestParam(required = false) String patientAge,
            @RequestParam(required = false) String patientGender,
            @RequestBody List<String> symptoms,
            @CurrentUser UserPrincipal principal) {
        // Patients may only triage their own symptoms. Doctors/admins may triage on behalf of any patient.
        boolean isPatient = principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PATIENT".equals(a.getAuthority()));
        if (isPatient && !principal.getId().equals(patientId)) {
            throw new MediBookException(
                    "Patients can only triage their own symptoms.",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return ApiResponse.ok(triageService.triageSymptoms(patientId, symptoms, patientAge, patientGender));
    }
}
