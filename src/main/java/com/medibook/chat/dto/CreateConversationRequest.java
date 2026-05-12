package com.medibook.chat.dto;

import jakarta.validation.constraints.NotNull;

public record CreateConversationRequest(
        @NotNull(message = "appointmentId is required") Long appointmentId) {}
