package com.medibook.domain.copilot.controller;

import com.medibook.domain.copilot.dto.CopilotDtos.*;
import com.medibook.domain.copilot.service.VisitCopilotService;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Visit Co-Pilot REST surface. Doctor-only.
 *
 *   POST /api/v1/copilot/start/{telemedicineSessionId}     → create-or-get session
 *   POST /api/v1/copilot/{copilotId}/transcript            → append chunk
 *   POST /api/v1/copilot/{copilotId}/brief                 → ask Claude to refresh brief
 *   GET  /api/v1/copilot/{copilotId}                       → poll snapshot
 *   POST /api/v1/copilot/{copilotId}/finalize              → save approved brief as note
 */
@RestController
@RequestMapping("/api/v1/copilot")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
@Tag(name = "Visit Co-Pilot", description = "Live AI assistant for the doctor during a telemedicine call")
public class VisitCopilotController {

    private final VisitCopilotService service;

    @PostMapping("/start/{telemedicineSessionId}")
    @Operation(summary = "Start or get the co-pilot session for a telemedicine call")
    public ResponseEntity<CopilotStateDto> start(
            @PathVariable Long telemedicineSessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.startOrGet(telemedicineSessionId, principal));
    }

    @PostMapping("/{copilotId}/transcript")
    @Operation(summary = "Append a transcript chunk captured from the live call")
    public ResponseEntity<CopilotStateDto> append(
            @PathVariable Long copilotId,
            @RequestBody AppendTranscriptRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.appendTranscript(copilotId, request, principal));
    }

    @PostMapping("/{copilotId}/brief")
    @Operation(summary = "Regenerate the AI brief from the rolling transcript")
    public ResponseEntity<CopilotStateDto> refreshBrief(
            @PathVariable Long copilotId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.refreshBrief(copilotId, principal));
    }

    @GetMapping("/{copilotId}")
    @Operation(summary = "Get current co-pilot snapshot (transcript + latest brief)")
    public ResponseEntity<CopilotStateDto> get(
            @PathVariable Long copilotId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.getState(copilotId, principal));
    }

    @PostMapping("/{copilotId}/finalize")
    @Operation(summary = "Save the doctor-approved brief as a consultation note")
    public ResponseEntity<CopilotStateDto> finalize(
            @PathVariable Long copilotId,
            @RequestBody FinalizeRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.finalize(copilotId, request, principal));
    }
}
