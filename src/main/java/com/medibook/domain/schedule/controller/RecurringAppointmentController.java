package com.medibook.domain.schedule.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.schedule.dto.AppointmentSeriesResponse;
import com.medibook.domain.schedule.dto.RecurringAppointmentRequest;
import com.medibook.domain.schedule.service.RecurringAppointmentService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/appointments/recurring")
@RequiredArgsConstructor
@Tag(name = "Recurring Appointments", description = "Recurring appointment series management")
public class RecurringAppointmentController {

    private final RecurringAppointmentService recurringService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Create a recurring appointment series")
    public ApiResponse<AppointmentSeriesResponse> createSeries(
            @Valid @RequestBody RecurringAppointmentRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(recurringService.createSeries(request, principal));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Get my active recurring series")
    public ApiResponse<Page<AppointmentSeriesResponse>> getMySeries(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(recurringService.getMySeries(principal, pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'ADMIN')")
    @Operation(summary = "Cancel a recurring appointment series and all future occurrences")
    public ApiResponse<Void> cancelSeries(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        recurringService.cancelSeries(id, principal);
        return ApiResponse.ok(null);
    }
}
