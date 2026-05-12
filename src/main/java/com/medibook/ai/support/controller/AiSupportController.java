package com.medibook.ai.support.controller;

import com.medibook.ai.support.dto.SupportChatRequest;
import com.medibook.ai.support.dto.SupportChatResponse;
import com.medibook.ai.support.service.AiSupportService;
import com.medibook.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the MediBook Assistant support chat widget.
 *
 * Authentication is OPTIONAL — unauthenticated users may ask support questions.
 * If a valid JWT is present, the authenticated user's context is used for
 * role-aware logging; no private patient/doctor data is ever returned.
 *
 * POST /api/v1/ai/chat — permitted to all (see SecurityConfig.PUBLIC_ENDPOINTS)
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
@RequiredArgsConstructor
@Tag(name = "AI Support", description = "MediBook Assistant support chat widget")
public class AiSupportController {

    private final AiSupportService aiSupportService;

    @PostMapping
    @Operation(
            summary     = "Send a support message to MediBook Assistant",
            description = "Classifies the message, enforces safety rules, and returns an AI-generated " +
                          "support reply. Authentication is optional — pass a Bearer token to enable " +
                          "role-aware context. PHI is never sent to external providers.",
            security    = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ApiResponse<SupportChatResponse>> chat(
            @Valid @RequestBody SupportChatRequest request,
            Authentication authentication) {

        SupportChatResponse response = aiSupportService.chat(request, authentication);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
