package com.medibook.domain.telemedicine.dto;

import com.medibook.domain.telemedicine.entity.ChatMessage;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatMessageResponse {

    private Long id;
    private Long senderId;
    private String senderName;
    private String senderRole;
    private String message;
    private LocalDateTime sentAt;
    private boolean system;

    public static ChatMessageResponse fromEntity(ChatMessage m) {
        return ChatMessageResponse.builder()
                .id(m.getId())
                .senderId(m.getSender().getId())
                .senderName(m.getSender().getFullName())
                .senderRole(m.getSenderRole())
                .message(m.getMessage())
                .sentAt(m.getSentAt())
                .system(m.isSystem())
                .build();
    }
}
