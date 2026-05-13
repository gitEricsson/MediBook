package com.medibook.domain.telemedicine.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.telemedicine.dto.ChatMessageRequest;
import com.medibook.domain.telemedicine.dto.ChatMessageResponse;
import com.medibook.domain.telemedicine.dto.TelemedicineSessionResponse;
import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import com.medibook.domain.telemedicine.service.TelemedicineSessionService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/telemedicine/sessions")
@RequiredArgsConstructor
@Tag(name = "Telemedicine", description = "Telemedicine session operations")
public class TelemedicineController {

    private final TelemedicineSessionService sessionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'ADMIN')")
    @Operation(summary = "Create a telemedicine session for a telemedicine appointment")
    public ApiResponse<TelemedicineSessionResponse> createSession(
            @RequestParam Long appointmentId,
            @RequestParam(defaultValue = "true") boolean patientConsent,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(sessionService.createSession(appointmentId, patientConsent, principal));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'ADMIN')")
    @Operation(summary = "Get telemedicine session details")
    public ApiResponse<TelemedicineSessionResponse> getSession(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(sessionService.getSession(id, principal));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Transition session status (WAITING → ACTIVE → COMPLETED)")
    public ApiResponse<TelemedicineSessionResponse> transitionStatus(
            @PathVariable Long id,
            @RequestParam TelemedicineSessionStatus status,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(sessionService.transitionStatus(id, status, principal));
    }

    @PutMapping("/{id}/call-note-draft")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Save call note draft — MUST be reviewed before finalizing")
    public ApiResponse<TelemedicineSessionResponse> saveCallNoteDraft(
            @PathVariable Long id,
            @RequestBody String noteDraft,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(sessionService.saveCallNoteDraft(id, noteDraft, principal));
    }

    @PostMapping("/{id}/call-note-draft/approve")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Doctor approves call note draft — marks as reviewed")
    public ApiResponse<TelemedicineSessionResponse> approveCallNote(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(sessionService.approveCallNote(id, principal));
    }

    @PostMapping("/{id}/chat")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR')")
    @Operation(summary = "Send a chat message in a session")
    public ApiResponse<ChatMessageResponse> sendMessage(
            @PathVariable Long id,
            @Valid @RequestBody ChatMessageRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(sessionService.sendChatMessage(id, request, principal));
    }

    @GetMapping("/{id}/chat")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'ADMIN')")
    @Operation(summary = "Get chat history for a session")
    public ApiResponse<List<ChatMessageResponse>> getChatHistory(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(sessionService.getChatHistory(id, principal));
    }
}
