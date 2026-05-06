package com.medibook.domain.notification.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.notification.entity.Notification;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "In-app notification inbox")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "Get recent notifications (last 30)")
    public ResponseEntity<ApiResponse<List<Notification>>> getRecent(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getRecent(principal.getId())));
    }

    @GetMapping("/unread")
    @Operation(summary = "Get unread notifications")
    public ResponseEntity<ApiResponse<List<Notification>>> getUnread(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getUnread(principal.getId())));
    }
}
