package com.medibook.domain.telemedicine.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.telemedicine.dto.CallParticipantRequest;
import com.medibook.domain.telemedicine.dto.ChatMessageRequest;
import com.medibook.domain.telemedicine.dto.ChatMessageResponse;
import com.medibook.domain.telemedicine.dto.EndCallRequest;
import com.medibook.domain.telemedicine.dto.TelemedicineSessionResponse;
import com.medibook.domain.telemedicine.dto.VideoCallResponse;
import com.medibook.domain.telemedicine.dto.VideoTokenResponse;
import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import com.medibook.domain.telemedicine.service.TelemedicineCallService;
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
    private final TelemedicineCallService callService;

    @PostMapping("/appointment/{appointmentId}/start-video-call")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR')")
    @Operation(summary = "Start or reuse an active Twilio Video call for an appointment")
    public ApiResponse<VideoCallResponse> startVideoCall(
            @PathVariable Long appointmentId,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(callService.startVideoCall(appointmentId, principal));
    }

    @PostMapping("/{id}/token")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR')")
    @Operation(summary = "Generate a short-lived Twilio Video token for a call session")
    public ApiResponse<VideoTokenResponse> getVideoToken(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(callService.getToken(id, principal));
    }

    @PostMapping("/{id}/join")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR')")
    @Operation(summary = "Mark the authenticated participant as joined")
    public ApiResponse<VideoCallResponse> joinCall(
            @PathVariable Long id,
            @RequestBody(required = false) CallParticipantRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(callService.join(id, request, principal));
    }

    @PostMapping("/{id}/leave")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR')")
    @Operation(summary = "Mark the authenticated participant as left")
    public ApiResponse<VideoCallResponse> leaveCall(
            @PathVariable Long id,
            @RequestBody(required = false) CallParticipantRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(callService.leave(id, request, principal));
    }

    @PostMapping("/{id}/end-call")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'ADMIN')")
    @Operation(summary = "End the active Twilio Video call")
    public ApiResponse<VideoCallResponse> endCall(
            @PathVariable Long id,
            @RequestBody(required = false) EndCallRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(callService.endCall(id, request == null ? null : request.reason(), principal));
    }

    @GetMapping("/appointment/{appointmentId}/active-call")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'ADMIN')")
    @Operation(summary = "Get the active call for an appointment, if one exists")
    public ApiResponse<VideoCallResponse> getActiveCall(
            @PathVariable Long appointmentId,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(callService.getActiveCall(appointmentId, principal));
    }

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
