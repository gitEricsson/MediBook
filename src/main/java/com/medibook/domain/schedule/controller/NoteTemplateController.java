package com.medibook.domain.schedule.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.schedule.entity.NoteTemplate;
import com.medibook.domain.schedule.service.NoteTemplateService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/note-templates")
@RequiredArgsConstructor
@Tag(name = "Note Templates", description = "Consultation note template management")
public class NoteTemplateController {

    private final NoteTemplateService templateService;

    @GetMapping("/doctors/{doctorId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Get note templates available to a doctor")
    public ApiResponse<List<NoteTemplate>> getTemplates(@PathVariable Long doctorId) {
        return ApiResponse.ok(templateService.getTemplatesForDoctor(doctorId));
    }

    @PostMapping("/doctors/{doctorId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Create a custom note template for a doctor")
    public ApiResponse<NoteTemplate> createTemplate(
            @PathVariable Long doctorId,
            @RequestBody Map<String, String> body,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(templateService.createTemplate(
                body.get("name"),
                body.get("templateType"),
                body.get("content"),
                doctorId,
                principal));
    }
}
