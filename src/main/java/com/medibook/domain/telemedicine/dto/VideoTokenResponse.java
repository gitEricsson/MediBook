package com.medibook.domain.telemedicine.dto;

import lombok.Builder;

import java.time.Instant;

@Builder
public record VideoTokenResponse(
        String token,
        String roomName,
        String identity,
        Instant expiresAt
) {}
