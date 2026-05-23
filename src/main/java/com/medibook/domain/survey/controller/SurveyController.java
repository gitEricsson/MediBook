package com.medibook.domain.survey.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.survey.dto.SurveyRequest;
import com.medibook.domain.survey.dto.SurveyResponse;
import com.medibook.domain.survey.service.SurveyService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/surveys")
@RequiredArgsConstructor
@Tag(name = "Post-Consultation Surveys", description = "Private patient feedback — admin access only for results")
@SecurityRequirement(name = "bearerAuth")
public class SurveyController {

    private final SurveyService surveyService;

    /** Patient submits structured feedback after a completed consultation. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Submit post-consultation feedback (patient only, stored privately)")
    public ApiResponse<Void> submit(
            @Valid @RequestBody SurveyRequest request,
            @CurrentUser UserPrincipal principal) {
        surveyService.submitSurvey(request, principal);
        return ApiResponse.<Void>ok(null);
    }

    /** Patient checks whether they have already submitted feedback for an appointment. */
    @GetMapping("/check/{appointmentId}")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Check if feedback has already been submitted for this appointment")
    public ApiResponse<Boolean> checkSubmitted(
            @PathVariable Long appointmentId,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(surveyService.hasSurveyForAppointment(appointmentId, principal));
    }

    /** Admin — all survey responses, newest first. */
    @GetMapping("/admin")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List all survey responses (admin/super_admin only)")
    public ApiResponse<Page<SurveyResponse>> getAllSurveys(
            @PageableDefault(size = 20, sort = "submittedAt") Pageable pageable) {
        return ApiResponse.ok(surveyService.getAllSurveys(pageable));
    }

    /** Admin — survey responses for a specific doctor. */
    @GetMapping("/admin/doctor/{doctorId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List survey responses for a specific doctor (admin/super_admin only)")
    public ApiResponse<Page<SurveyResponse>> getSurveysForDoctor(
            @PathVariable Long doctorId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(surveyService.getSurveysForDoctor(doctorId, pageable));
    }
}
