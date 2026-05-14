package com.medibook.domain.notification.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.notification.dto.NotificationResponse;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Notification REST API — provides the initial load and offline-recovery fallback.
 *
 * Real-time updates are pushed over WebSocket STOMP:
 *   Endpoint  : ws://host/ws?token=<JWT>
 *   Subscribe : /user/queue/notifications
 *
 * Recommended frontend flow:
 *   1. On login/page load  → GET /api/v1/notifications?unread=true  (badge count)
 *   2. WebSocket connect   → subscribe /user/queue/notifications
 *   3. On WS message       → increment badge, prepend to list, show toast
 *   4. On WS disconnect    → reconnect with exponential backoff, then re-fetch count
 */
@RestController
@RequestMapping({"/api/v1/me/notifications", "/api/v1/notifications"})
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Notification inbox — REST fallback for WebSocket push")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "List recent notifications (or unread only when ?unread=true)")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getNotifications(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) boolean unread) {
        List<NotificationResponse> list = unread
                ? notificationService.getUnread(principal.getId())
                : notificationService.getRecent(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @GetMapping("/unread")
    @Operation(summary = "Get unread notifications")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getUnreadNotifications(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getUnread(principal.getId())));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get current unread notification count (for badge refresh)")
    public ResponseEntity<ApiResponse<Long>> getUnreadCount(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getUnreadCount(principal.getId())));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark a single notification as read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @CurrentUser UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody MarkReadRequest body) {
        notificationService.markAsRead(principal.getId(), body.getCreatedAt(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all notifications in inbox as read")
    public ResponseEntity<ApiResponse<Void>> markAllRead(@CurrentUser UserPrincipal principal) {
        notificationService.markAllRead(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @Data
    static class MarkReadRequest {
        private Instant createdAt;
    }
}
