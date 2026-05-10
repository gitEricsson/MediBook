package com.medibook.domain.consultation.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.consultation.dto.ConsultationNoteRequest;
import com.medibook.domain.consultation.dto.ConsultationNoteResponse;
import com.medibook.domain.consultation.service.ConsultationNoteService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/consultation-notes")
@RequiredArgsConstructor
@Tag(name = "Consultation Notes", description = "PHI-encrypted clinical notes per appointment")
@SecurityRequirement(name = "bearerAuth")
public class ConsultationNoteController {

    private final ConsultationNoteService noteService;

    @PostMapping("/appointment/{appointmentId}")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Create a consultation note for an appointment (Doctor/Admin)")
    public ResponseEntity<ApiResponse<ConsultationNoteResponse>> create(
            @PathVariable Long appointmentId,
            @Valid @RequestBody ConsultationNoteRequest request,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(noteService.create(appointmentId, request, principal)));
    }

    @GetMapping("/appointment/{appointmentId}")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Get consultation note by appointment (Doctor/Admin)")
    public ResponseEntity<ApiResponse<ConsultationNoteResponse>> getByAppointment(
            @PathVariable Long appointmentId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(noteService.getByAppointment(appointmentId, principal)));
    }

    @GetMapping("/my-history")
    @Operation(summary = "Get patient's full consultation history (Patient)")
    public ResponseEntity<ApiResponse<List<ConsultationNoteResponse>>> getMyHistory(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(noteService.getPatientHistory(principal.getId())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Update a consultation note (Doctor/Admin)")
    public ResponseEntity<ApiResponse<ConsultationNoteResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ConsultationNoteRequest request,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(noteService.update(id, request, principal)));
    }
}
