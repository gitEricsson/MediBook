package com.medibook.domain.waitlist.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.waitlist.dto.WaitlistRequest;
import com.medibook.domain.waitlist.dto.WaitlistResponse;
import com.medibook.domain.waitlist.service.WaitlistService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/waitlist")
@RequiredArgsConstructor
@Tag(name = "Waitlist", description = "Doctor availability waitlist operations")
public class WaitlistController {

    private final WaitlistService waitlistService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Join the waitlist for a doctor or specialty")
    public ApiResponse<WaitlistResponse> joinWaitlist(
            @RequestBody WaitlistRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(waitlistService.joinWaitlist(request, principal));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Get my waitlist entries")
    public ApiResponse<Page<WaitlistResponse>> getMyWaitlist(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(waitlistService.getMyWaitlist(principal, pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'ADMIN')")
    @Operation(summary = "Leave the waitlist")
    public ApiResponse<Void> leaveWaitlist(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        waitlistService.leaveWaitlist(id, principal);
        return ApiResponse.ok(null);
    }
}
