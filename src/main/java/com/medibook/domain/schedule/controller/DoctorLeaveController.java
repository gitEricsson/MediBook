package com.medibook.domain.schedule.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.schedule.dto.DoctorLeaveRequest;
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
    public ApiResponse<List<DoctorLeave>> getPendingLeaves() {
        return ApiResponse.ok(leaveService.getAllPendingLeaves());
    }

    @PostMapping("/api/v1/admin/leaves/{leaveId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Approve a pending leave request")
    public ApiResponse<DoctorLeave> approveLeave(
            @PathVariable Long leaveId,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(leaveService.approveLeave(leaveId, principal));
    }

    @PostMapping("/api/v1/admin/leaves/{leaveId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Reject a pending leave request")
    public ApiResponse<DoctorLeave> rejectLeave(
            @PathVariable Long leaveId,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(leaveService.rejectLeave(leaveId, principal));
    }
}
