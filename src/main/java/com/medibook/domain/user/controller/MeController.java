package com.medibook.domain.user.controller;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.response.ApiResponse;
import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "User Profile", description = "Current user profile management")
@SecurityRequirement(name = "bearerAuth")
public class MeController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    @Operation(summary = "Get current user profile")
    public ResponseEntity<ApiResponse<UserResponse>> getProfile(@CurrentUser UserPrincipal principal) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new MediBookException("User not found", HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(ApiResponse.ok(UserResponse.fromUser(user)));
    }

    @PatchMapping
    @Operation(summary = "Update profile details")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, Object> updates) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new MediBookException("User not found", HttpStatus.NOT_FOUND));

        if (updates.containsKey("firstName")) user.setFirstName((String) updates.get("firstName"));
        if (updates.containsKey("lastName")) user.setLastName((String) updates.get("lastName"));
        if (updates.containsKey("phone")) user.setPhone((String) updates.get("phone"));
        if (updates.containsKey("dateOfBirth")) {
             // Handle date parsing
        }

        return ResponseEntity.ok(ApiResponse.ok(UserResponse.fromUser(userRepository.save(user))));
    }

    @PostMapping("/password")
    @Operation(summary = "Update password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, String> request) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new MediBookException("User not found", HttpStatus.NOT_FOUND));

        String currentPassword = request.get( "currentPassword");
        String newPassword = request.get("newPassword");

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new MediBookException("Invalid current password", HttpStatus.BAD_REQUEST);
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PatchMapping("/notifications")
    @Operation(summary = "Update notification preferences")
    public ResponseEntity<ApiResponse<UserResponse>> updateNotificationPrefs(
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, Boolean> prefs) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new MediBookException("User not found", HttpStatus.NOT_FOUND));

        if (prefs.containsKey("email")) user.setEmailNotifications(prefs.get("email"));
        if (prefs.containsKey("sms")) user.setSmsNotifications(prefs.get("sms"));

        return ResponseEntity.ok(ApiResponse.ok(UserResponse.fromUser(userRepository.save(user))));
    }

    @PatchMapping("/locale")
    @Operation(summary = "Update language preference")
    public ResponseEntity<ApiResponse<UserResponse>> updateLocale(
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new MediBookException("User not found", HttpStatus.NOT_FOUND));

        if (body.containsKey("language")) user.setLocale(body.get("language"));

        return ResponseEntity.ok(ApiResponse.ok(UserResponse.fromUser(userRepository.save(user))));
    }
}
