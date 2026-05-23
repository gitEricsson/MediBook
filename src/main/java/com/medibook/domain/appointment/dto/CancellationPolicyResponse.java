package com.medibook.domain.appointment.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CancellationPolicyResponse {
    private int noticeMinutes;
    private boolean feeApplies;
}
