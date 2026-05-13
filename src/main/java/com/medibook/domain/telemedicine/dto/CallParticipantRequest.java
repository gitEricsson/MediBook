package com.medibook.domain.telemedicine.dto;

public record CallParticipantRequest(
        Boolean cameraEnabled,
        Boolean microphoneEnabled
) {}
