package com.medibook.domain.schedule.controller;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.common.response.ApiResponse;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.schedule.dto.AdminLeaveResponse;
import com.medibook.domain.schedule.dto.DoctorLeaveRequest;
import com.medibook.domain.schedule.dto.DoctorLeaveResponse;
import com.medibook.domain.schedule.entity.DoctorLeave;
import com.medibook.domain.schedule.service.DoctorLeaveService;
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
@RequiredArgsConstructor
@Tag(name = "Doctor Leave", description = "Doctor leave and hospital holiday management")
public class DoctorLeaveController {

    private final DoctorLeaveService leaveService;
    private final DoctorRepository doctorRepository;

    /** Resolve the Doctor entity PK from the currently-authenticated user. */
    private Long resolveOwnDoctorId(UserPrincipal principal) {
        return doctorRepository.findByUserId(principal.getId())
                .map(d -> d.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "userId", principal.getId()));
    }

    @GetMapping("/api/v1/me/leaves")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Get the logged-in doctor's leave records (resolves doctorId from JWT)")
    public ApiResponse<List<DoctorLeaveResponse>> getMyLeaves(@CurrentUser UserPrincipal principal) {
        List<DoctorLeaveResponse> body = leaveService.getLeaveForDoctor(resolveOwnDoctorId(principal))
                .stream().map(DoctorLeaveResponse::from).toList();
        return ApiResponse.ok(body);
    }

    @PostMapping("/api/v1/me/leaves")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Create a leave request for the logged-in doctor")
    public ApiResponse<DoctorLeaveResponse> createMyLeave(
            @Valid @RequestBody DoctorLeaveRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(
                DoctorLeaveResponse.from(
                        leaveService.createLeave(resolveOwnDoctorId(principal), request, principal)));
    }

    @PostMapping("/api/v1/doctors/{doctorId}/leaves")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Create a doctor leave request (PENDING for doctors, auto-APPROVED for admins)")
    public ApiResponse<DoctorLeave> createLeave(
            @PathVariable Long doctorId,
            @Valid @RequestBody DoctorLeaveRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(leaveService.createLeave(doctorId, request, principal));
    }

    @GetMapping("/api/v1/doctors/{doctorId}/leaves")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get leave records for a doctor")
    public ApiResponse<List<DoctorLeave>> getLeaves(@PathVariable Long doctorId) {
        return ApiResponse.ok(leaveService.getLeaveForDoctor(doctorId));
    }

    @GetMapping("/api/v1/admin/leaves/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get all pending leave requests for admin review")
    public ApiResponse<List<AdminLeaveResponse>> getPendingLeaves() {
        return ApiResponse.ok(leaveService.getAllPendingLeaveResponses());
    }

    @GetMapping("/api/v1/admin/leaves")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List leave requests across all doctors; optional ?status= filter")
    public ApiResponse<List<AdminLeaveResponse>> listAdminLeaves(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(leaveService.getAllLeaveResponses(status));
    }

    @PostMapping("/api/v1/admin/leaves/{leaveId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Approve a pending leave request")
    public ApiResponse<AdminLeaveResponse> approveLeave(
            @PathVariable Long leaveId,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(leaveService.approveLeaveResponse(leaveId, principal));
    }

    @PostMapping("/api/v1/admin/leaves/{leaveId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Reject a pending leave request")
    public ApiResponse<AdminLeaveResponse> rejectLeave(
            @PathVariable Long leaveId,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(leaveService.rejectLeaveResponse(leaveId, principal));
    }
}
