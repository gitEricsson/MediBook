package com.medibook.domain.user.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.user.dto.*;
import com.medibook.domain.user.service.AuthService;
import com.medibook.domain.user.service.UserService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register, login, 2FA, password reset, and token management")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    // ─── Register ────────────────────────────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Register a new patient account — returns tokens immediately")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Registered"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email taken")
    })
    public ResponseEntity<ApiResponse<TokenResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(authService.register(request)));
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    @PostMapping("/login")
    @Operation(summary = "Email/password login — returns token pair or 2FA challenge")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(request)));
    }

    // ─── 2FA ─────────────────────────────────────────────────────────────────

    @PostMapping("/2fa/verify")
    @Operation(summary = "Complete 2FA with the emailed OTP")
    public ResponseEntity<ApiResponse<TokenResponse>> verifyTwoFactor(
            @Valid @RequestBody TwoFactorVerifyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.verifyTwoFactor(request)));
    }

    // ─── Token Lifecycle ─────────────────────────────────────────────────────

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token and receive a new access token")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke refresh token and end the current session")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.noContent("Logged out successfully"));
    }

    // ─── Current User ────────────────────────────────────────────────────────

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the authenticated user's profile, role, and permissions")
    public ResponseEntity<ApiResponse<UserResponse>> me(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserById(principal.getId())));
    }

    // ─── Password Reset ──────────────────────────────────────────────────────

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset link — always 204, no user enumeration")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using the token from the reset email")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password changed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Token invalid or expired")
    })
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.noContent("Password reset successfully"));
    }

    // ─── Email Verification ──────────────────────────────────────────────────

    @PostMapping("/email/verify")
    @Operation(summary = "Verify email address using the token from the verification email")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Email verified"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Token invalid or expired")
    })
    public ResponseEntity<ApiResponse<Void>> verifyEmail(
            @Valid @RequestBody EmailVerifyRequest request) {
        authService.verifyEmail(request);
        return ResponseEntity.ok(ApiResponse.noContent("Email verified successfully"));
    }

    @PostMapping("/email/resend")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Resend the email verification link")
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @CurrentUser UserPrincipal principal) {
        authService.resendVerificationEmail(principal.getId());
        return ResponseEntity.ok(ApiResponse.noContent("Verification email sent"));
    }
}
