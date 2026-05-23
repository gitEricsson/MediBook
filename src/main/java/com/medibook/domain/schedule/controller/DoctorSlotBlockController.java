package com.medibook.domain.schedule.controller;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.common.response.ApiResponse;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.schedule.dto.SlotBlockRequest;
import com.medibook.domain.schedule.dto.SlotBlockResponse;
import com.medibook.domain.schedule.service.DoctorSlotBlockService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Doctor Slot Blocks", description = "Ad-hoc per-day slot unavailability")
@SecurityRequirement(name = "bearerAuth")
public class DoctorSlotBlockController {

    private final DoctorSlotBlockService slotBlockService;
    private final DoctorRepository       doctorRepository;

    private Long resolveOwnDoctorId(UserPrincipal principal) {
        return doctorRepository.findByUserId(principal.getId())
                .map(d -> d.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "userId", principal.getId()));
    }

    // ── Doctor (self) ───────────────────────────────────────────────────────
    @GetMapping("/api/v1/me/slot-blocks")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "List the logged-in doctor's slot blocks in a date window")
    public ApiResponse<List<SlotBlockResponse>> listMyBlocks(
            @CurrentUser UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(slotBlockService.listForDoctor(resolveOwnDoctorId(principal), from, to));
    }

    @PostMapping("/api/v1/me/slot-blocks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Create a slot block for the logged-in doctor")
    public ApiResponse<SlotBlockResponse> createOwnBlock(
            @Valid @RequestBody SlotBlockRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.created(slotBlockService.create(resolveOwnDoctorId(principal), request, principal));
    }

    @DeleteMapping("/api/v1/me/slot-blocks/{id}")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Delete a slot block owned by the logged-in doctor")
    public ApiResponse<Void> deleteOwnBlock(@PathVariable Long id, @CurrentUser UserPrincipal principal) {
        slotBlockService.delete(id, principal);
        return ApiResponse.noContent("Slot block removed");
    }

    // ── Admin audit ─────────────────────────────────────────────────────────
    @GetMapping("/api/v1/admin/slot-blocks")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Audit ad-hoc slot blocks across all doctors in a date window")
    public ApiResponse<List<SlotBlockResponse>> listAdmin(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(slotBlockService.listAdminAudit(from, to));
    }

    @DeleteMapping("/api/v1/admin/slot-blocks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Remove a slot block (admin override)")
    public ApiResponse<Void> deleteAdmin(@PathVariable Long id, @CurrentUser UserPrincipal principal) {
        slotBlockService.delete(id, principal);
        return ApiResponse.noContent("Slot block removed");
    }
}
