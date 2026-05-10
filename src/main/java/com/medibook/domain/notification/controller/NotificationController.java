package com.medibook.domain.notification.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.notification.dto.NotificationResponse;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/me/notifications", "/api/v1/notifications"})
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Current user notification inbox")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "Get list of notifications")
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

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark single notification as read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @CurrentUser UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody MarkReadRequest body) {
        notificationService.markAsRead(principal.getId(), body.getCreatedAt(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all notifications in inbox as read")
    public ResponseEntity<ApiResponse<Void>> markAllRead(@CurrentUser UserPrincipal principal) {
        notificationService.markAllRead(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get current unread notification count")
    public ResponseEntity<ApiResponse<Long>> getUnreadCount(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getUnreadCount(principal.getId())));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Establish SSE stream for live notifications")
    public SseEmitter streamNotifications(@CurrentUser UserPrincipal principal) {
        return notificationService.subscribe(principal.getId());
    }

    @Data
    static class MarkReadRequest {
        private Instant createdAt;
    }
}
