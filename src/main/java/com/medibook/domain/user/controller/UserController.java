package com.medibook.domain.user.controller;

import com.medibook.audit.entity.AuditLog;
import com.medibook.common.response.ApiResponse;
import com.medibook.domain.user.dto.ChangeRoleRequest;
import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.service.UserService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile and account management")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user's profile")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserById(principal.getId())));
    }

    @PutMapping("/me")
    @Operation(summary = "Update the authenticated user's profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UserResponse.UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateProfile(principal.getId(), request)));
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace profile picture (max 2 MB, JPEG/PNG/WEBP)")
    public ResponseEntity<ApiResponse<UserResponse>> uploadAvatar(
            @CurrentUser UserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok(userService.uploadAvatar(principal.getId(), file)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get a user by ID (Admin only)")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserById(id)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List all users with pagination (Admin only)")
    public ResponseEntity<ApiResponse<Page<UserResponse>>> listUsers(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getAllUsers(pageable)));
    }

    @PostMapping("/me/2fa/enable")
    @Operation(summary = "Enable 2FA for the authenticated user")
    public ResponseEntity<ApiResponse<Void>> enableTwoFactor(@CurrentUser UserPrincipal principal) {
        userService.enableTwoFactor(principal.getId());
        return ResponseEntity.ok(ApiResponse.noContent("Two-factor authentication enabled"));
    }

    @PatchMapping("/{id}/disable")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Disable a user account (Admin only)")
    public ResponseEntity<ApiResponse<Void>> disableUser(@PathVariable Long id) {
        userService.disableUser(id);
        return ResponseEntity.ok(ApiResponse.noContent("User disabled successfully"));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Re-enable a disabled user account (Admin only)")
    public ResponseEntity<ApiResponse<UserResponse>> enableUser(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.enableUser(id)));
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Change user role — cannot promote to ADMIN (Admin only)")
    public ResponseEntity<ApiResponse<UserResponse>> changeRole(
            @PathVariable Long id,
            @Valid @RequestBody ChangeRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.changeRole(id, request.getRole())));
    }

    @PostMapping("/{id}/revoke-sessions")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Force-expire all refresh tokens for a user (Admin only)")
    public ResponseEntity<ApiResponse<String>> revokeSessions(@PathVariable Long id) {
        int count = userService.revokeAllSessions(id);
        return ResponseEntity.ok(ApiResponse.ok("Revoked " + count + " session(s)"));
    }

    @GetMapping("/{id}/audit")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "View recent audit log entries for a user (Admin only)")
    public ResponseEntity<ApiResponse<List<AuditLog>>> getAuditLog(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getAuditLog(id)));
    }
}
