package com.medibook.domain.telemedicine.dto;

import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDateTime;

@Builder
public record VideoCallResponse(
        Long sessionId,
        Long appointmentId,
        Long conversationId,
        Long patientId,
        Long doctorId,
        TelemedicineSessionStatus status,
        String twilioRoomSid,
        String roomName,
        String token,
        String identity,
        Instant tokenExpiresAt,
        LocalDateTime startedAt,
        LocalDateTime acceptedAt,
        LocalDateTime endedAt,
        Integer durationSeconds
) {}
