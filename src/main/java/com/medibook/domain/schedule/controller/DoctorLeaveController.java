package com.medibook.domain.schedule.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.schedule.dto.DoctorLeaveRequest;
import com.medibook.domain.schedule.entity.DoctorLeave;
import com.medibook.domain.schedule.entity.HospitalHoliday;
import com.medibook.domain.schedule.service.DoctorLeaveService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/v1/doctors/{doctorId}/leaves")
@RequiredArgsConstructor
@Tag(name = "Doctor Leave", description = "Doctor leave and hospital holiday management")
public class DoctorLeaveController {

    private final DoctorLeaveService leaveService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Create a doctor leave period")
    public ApiResponse<DoctorLeave> createLeave(
            @PathVariable Long doctorId,
            @Valid @RequestBody DoctorLeaveRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(leaveService.createLeave(doctorId, request, principal));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @Operation(summary = "Get leave records for a doctor")
    public ApiResponse<List<DoctorLeave>> getLeaves(@PathVariable Long doctorId) {
        return ApiResponse.ok(leaveService.getLeaveForDoctor(doctorId));
    }
}
