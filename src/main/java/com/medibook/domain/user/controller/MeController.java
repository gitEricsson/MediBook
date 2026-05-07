package com.medibook.domain.user.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.user.dto.ChangePasswordRequest;
import com.medibook.domain.user.dto.UpdateProfileRequest;
import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.service.UserProfileService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "User Profile", description = "Current user profile management")
@SecurityRequirement(name = "bearerAuth")
public class MeController {

    private final UserProfileService userProfileService;

    @GetMapping
    @Operation(summary = "Get current user profile")
    public ResponseEntity<ApiResponse<UserResponse>> getProfile(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(userProfileService.getProfile(principal.getId())));
    }

    @PatchMapping
    @Operation(summary = "Update profile details")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userProfileService.updateProfile(principal.getId(), request)));
    }

    @PostMapping("/password")
    @Operation(summary = "Update password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        userProfileService.changePassword(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PatchMapping("/notifications")
    @Operation(summary = "Update notification preferences")
    public ResponseEntity<ApiResponse<UserResponse>> updateNotificationPrefs(
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, Boolean> prefs) {
        return ResponseEntity.ok(ApiResponse.ok(
                userProfileService.updateNotificationPreferences(principal.getId(), prefs)));
    }

    @PatchMapping("/locale")
    @Operation(summary = "Update language preference")
    public ResponseEntity<ApiResponse<UserResponse>> updateLocale(
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        String language = body.get("language");
        return ResponseEntity.ok(ApiResponse.ok(
                userProfileService.updateLocale(principal.getId(), language)));
    }
}
