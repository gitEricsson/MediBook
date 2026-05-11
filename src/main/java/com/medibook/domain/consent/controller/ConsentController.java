package com.medibook.domain.consent.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.consent.dto.ConsentRequest;
import com.medibook.domain.consent.entity.UserConsent;
import com.medibook.domain.consent.service.ConsentService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/consents")
@RequiredArgsConstructor
@Tag(name = "Consents", description = "Patient consent management (HIPAA/GDPR/NDPR)")
public class ConsentController {

    private final ConsentService consentService;

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Grant or revoke a consent type")
    public ApiResponse<UserConsent> updateConsent(
            @Valid @RequestBody ConsentRequest request,
            @CurrentUser UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String ip = httpRequest.getHeader("X-Forwarded-For");
        if (ip == null) ip = httpRequest.getRemoteAddr();
        return ApiResponse.ok(consentService.updateConsent(request, principal, ip));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get all my consent records")
    public ApiResponse<List<UserConsent>> getMyConsents(@CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(consentService.getMyConsents(principal));
    }
}
