package com.medibook.domain.appointment.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class HoldResponse {
    private String holdId;
    private Instant expiresAt;
}
