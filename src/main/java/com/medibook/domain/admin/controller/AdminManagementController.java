package com.medibook.domain.admin.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.admin.dto.CreateAdminRequest;
import com.medibook.domain.admin.dto.ResetAdminPasswordRequest;
import com.medibook.domain.admin.dto.UpdateAdminRequest;
import com.medibook.domain.admin.service.AdminManagementService;
import com.medibook.domain.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/admins")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Super Admin — Admin Management", description = "SUPER_ADMIN only: create and manage admin users")
@SecurityRequirement(name = "bearerAuth")
public class AdminManagementController {

    private final AdminManagementService adminManagementService;

    @PostMapping
    @Operation(summary = "Create a new admin account")
    public ResponseEntity<ApiResponse<UserResponse>> createAdmin(
            @Valid @RequestBody CreateAdminRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(adminManagementService.createAdmin(request)));
    }

    @GetMapping
    @Operation(summary = "List admin accounts with optional search and pagination")
    public ResponseEntity<ApiResponse<Page<UserResponse>>> listAdmins(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(adminManagementService.listAdmins(q, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get admin by ID")
    public ResponseEntity<ApiResponse<UserResponse>> getAdmin(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(adminManagementService.getAdmin(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update admin profile (name, phone)")
    public ResponseEntity<ApiResponse<UserResponse>> updateAdmin(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAdminRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(adminManagementService.updateAdmin(id, request)));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate an admin account")
    public ResponseEntity<ApiResponse<Void>> activateAdmin(@PathVariable Long id) {
        adminManagementService.activateAdmin(id);
        return ResponseEntity.ok(ApiResponse.noContent("Admin activated"));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate an admin account (revokes all sessions)")
    public ResponseEntity<ApiResponse<Void>> deactivateAdmin(@PathVariable Long id) {
        adminManagementService.deactivateAdmin(id);
        return ResponseEntity.ok(ApiResponse.noContent("Admin deactivated"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete an admin account (disables and revokes all sessions)")
    public ResponseEntity<ApiResponse<Void>> deleteAdmin(@PathVariable Long id) {
        adminManagementService.deleteAdmin(id);
        return ResponseEntity.ok(ApiResponse.noContent("Admin deleted"));
    }

    @PostMapping("/{id}/reset-password")
    @Operation(summary = "Reset an admin's password (revokes all sessions)")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable Long id,
            @Valid @RequestBody ResetAdminPasswordRequest request) {
        adminManagementService.resetAdminPassword(id, request);
        return ResponseEntity.ok(ApiResponse.noContent("Password reset successfully"));
    }
}
