package com.medibook.chat.dto;

import java.time.LocalDateTime;

public record ConversationResponse(
        Long id,
        String twilioConversationSid,
        Long appointmentId,
        Long patientId,
        Long doctorId,
        String status,
        boolean aiEnabled,
        LocalDateTime createdAt) {}
