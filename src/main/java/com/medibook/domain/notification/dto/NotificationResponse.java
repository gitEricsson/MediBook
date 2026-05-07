package com.medibook.domain.notification.dto;

import com.medibook.domain.notification.entity.Notification;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class NotificationResponse {

    private UUID notificationId;
    private String title;
    private String message;
    private String type;
    private boolean read;
    private Instant readAt;
    private Instant createdAt;
    private Long appointmentId;

    public static NotificationResponse fromEntity(Notification n) {
        return NotificationResponse.builder()
                .notificationId(n.getNotificationId())
                .title(n.getTitle())
                .message(n.getMessage())
                .type(n.getType())
                .read(n.isRead())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .appointmentId(n.getAppointmentId())
                .build();
    }
}
