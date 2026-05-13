package com.medibook.ai.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupportChatRequest(

        @NotBlank(message = "message must not be blank")
        @Size(max = 2000, message = "message must not exceed 2000 characters")
        String message,

        /** Optional context hint supplied by the frontend (e.g. "support", "booking") */
        @Size(max = 50, message = "context must not exceed 50 characters")
        String context,

        /**
         * Optional client-generated session ID for conversation continuity.
         * If absent the service generates a new one.
         */
        @Size(max = 64, message = "sessionId must not exceed 64 characters")
        String sessionId
) {}
