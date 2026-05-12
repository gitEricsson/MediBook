package com.medibook.chat.dto;

import java.time.LocalDateTime;

public record AiSummaryResponse(
        Long conversationId,
        String summary,
        LocalDateTime generatedAt) {}
