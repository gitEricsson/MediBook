package com.medibook.chat.controller;

import com.medibook.ai.audit.entity.AiMessageAudit;
import com.medibook.ai.audit.service.AiAuditService;
import com.medibook.chat.dto.*;
import com.medibook.chat.entity.AiDraftResponse;
import com.medibook.chat.entity.UrgencyAlert;
import com.medibook.chat.service.ChatService;
import com.medibook.common.response.ApiResponse;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Tag(name = "AI Chat", description = "AI-assisted doctor-patient conversations via Twilio Conversations")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final ChatService    chatService;
    private final AiAuditService auditService;

    // ── Conversation ─────────────────────────────────────────────────────────

    @PostMapping("/conversations")
    @Operation(summary = "Create a new AI-assisted conversation for an appointment")
    public ResponseEntity<ApiResponse<ConversationResponse>> create(
            @Valid @RequestBody CreateConversationRequest req,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(chatService.createConversation(req.appointmentId(), principal.getId())));
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "Get all messages for a conversation")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> getMessages(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getMessages(id, principal.getId())));
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "Get conversation metadata")
    public ResponseEntity<ApiResponse<ConversationResponse>> getConversation(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getConversation(id, principal.getId())));
    }

    // ── AI Operations (Doctor only) ───────────────────────────────────────────

    @PostMapping("/{conversationId}/ai/summary")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Generate a conversation summary for doctor review (Doctor/Admin only)")
    public ResponseEntity<ApiResponse<AiSummaryResponse>> generateSummary(
            @PathVariable Long conversationId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.generateSummary(conversationId, principal.getId())));
    }

    @PostMapping("/{conversationId}/ai/draft")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Generate an AI draft reply for doctor approval (Doctor/Admin only)")
    public ResponseEntity<ApiResponse<AiDraftResponse>> generateDraft(
            @PathVariable Long conversationId,
            @Valid @RequestBody GenerateDraftRequest req,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(chatService.generateDraft(
                        conversationId, req.patientMessage(), principal.getId())));
    }

    @PostMapping("/{conversationId}/ai/draft/{draftId}/approve")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Approve and send an AI draft (optionally with edits). Doctor approval mandatory.")
    public ResponseEntity<ApiResponse<AiDraftResponse>> approveDraft(
            @PathVariable Long conversationId,
            @PathVariable Long draftId,
            @Valid @RequestBody(required = false) DraftApprovalRequest req,
            @CurrentUser UserPrincipal principal) {
        String editedBody = (req != null) ? req.editedBody() : null;
        return ResponseEntity.ok(ApiResponse.ok(
                chatService.approveDraft(conversationId, draftId, editedBody, principal.getId())));
    }

    @PostMapping("/{conversationId}/ai/draft/{draftId}/reject")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Reject an AI draft — it will not be sent")
    public ResponseEntity<ApiResponse<AiDraftResponse>> rejectDraft(
            @PathVariable Long conversationId,
            @PathVariable Long draftId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.rejectDraft(conversationId, draftId, principal.getId())));
    }

    // ── AI Operations (Patient) ───────────────────────────────────────────────

    @PostMapping("/{conversationId}/ai/intake")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Start or continue the AI-guided intake questionnaire (Patient only)")
    public ResponseEntity<ApiResponse<AiSummaryResponse>> runIntake(
            @PathVariable Long conversationId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.runIntake(conversationId, principal.getId())));
    }

    @PostMapping("/{conversationId}/ai/consent")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Grant or revoke AI participation consent (Patient only)")
    public ResponseEntity<ApiResponse<Void>> setConsent(
            @PathVariable Long conversationId,
            @Valid @RequestBody ConsentRequest req,
            @CurrentUser UserPrincipal principal,
            HttpServletRequest httpReq) {
        chatService.grantConsent(conversationId, principal.getId(), req.granted(),
                httpReq.getRemoteAddr(),
                httpReq.getHeader("User-Agent"));
        return ResponseEntity.ok(ApiResponse.noContent(req.granted() ? "Consent granted" : "Consent revoked"));
    }

    // ── Urgency ───────────────────────────────────────────────────────────────

    @PostMapping("/{conversationId}/urgency/{alertId}/acknowledge")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Acknowledge a medical urgency alert (Doctor/Admin only)")
    public ResponseEntity<ApiResponse<Void>> acknowledgeUrgency(
            @PathVariable Long conversationId,
            @PathVariable Long alertId,
            @CurrentUser UserPrincipal principal) {
        chatService.acknowledgeUrgency(conversationId, alertId, principal.getId());
        return ResponseEntity.ok(ApiResponse.noContent("Urgency alert acknowledged"));
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    @GetMapping("/{conversationId}/ai/audit")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Get AI audit trail for a conversation (Doctor/Admin only)")
    public ResponseEntity<ApiResponse<List<AiMessageAudit>>> getAudit(
            @PathVariable Long conversationId,
            @CurrentUser UserPrincipal principal) {
        // Authorization enforced by audit service (conversationId visible to doctor/admin only)
        return ResponseEntity.ok(ApiResponse.ok(auditService.getConversationAudit(conversationId)));
    }
}
