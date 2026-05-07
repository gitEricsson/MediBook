package com.medibook.domain.doctor.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScheduleSummaryResponse {
    private long done;
    private long upcoming;
    private long noShow;
    private long freeSlots;
}
