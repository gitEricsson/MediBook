package com.medibook.domain.pricing.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.pricing.dto.PricingPolicyRequest;
import com.medibook.domain.pricing.dto.PricingPolicyResponse;
import com.medibook.domain.pricing.service.PricingPolicyService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/pricing-policy")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin — Pricing Policy", description = "Hospital-wide pricing knobs (RUD)")
@SecurityRequirement(name = "bearerAuth")
public class AdminPricingController {

    private final PricingPolicyService service;

    @GetMapping
    @Operation(summary = "Read the current pricing policy")
    public ResponseEntity<ApiResponse<PricingPolicyResponse>> get() {
        return ResponseEntity.ok(ApiResponse.ok(PricingPolicyResponse.fromEntity(service.get())));
    }

    @PatchMapping
    @Operation(summary = "Update one or more pricing knobs (null fields are ignored)")
    public ResponseEntity<ApiResponse<PricingPolicyResponse>> patch(
            @Valid @RequestBody PricingPolicyRequest body,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Pricing policy updated",
                PricingPolicyResponse.fromEntity(service.update(body, principal.getId()))));
    }
}
