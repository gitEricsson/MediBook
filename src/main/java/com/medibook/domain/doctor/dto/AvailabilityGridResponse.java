package com.medibook.domain.doctor.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AvailabilityGridResponse {
    private List<DaySlots> days;

    @Data
    @Builder
    public static class DaySlots {
        private LocalDate date;
        private List<SlotInfo> slots;
    }

    @Data
    @Builder
    public static class SlotInfo {
        private LocalDateTime start;
        private LocalDateTime end;
        private String status; // OPEN | HELD | TAKEN
    }
}
