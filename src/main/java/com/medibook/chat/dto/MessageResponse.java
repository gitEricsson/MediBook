package com.medibook.chat.dto;

import java.time.LocalDateTime;

public record MessageResponse(
        Long id,
        Long conversationId,
        String twilioMessageSid,
        Long senderId,
        String senderRole,
        String body,
        boolean aiGenerated,
        String safetyLabel,
        LocalDateTime createdAt) {}
