package com.medibook.domain.doctor.dto;

import com.medibook.domain.appointment.dto.AppointmentResponse;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@Builder
public class ScheduleDayResponse {
    private LocalDate date;
    private LocalTime workStart;
    private LocalTime workEnd;
    private List<TimeSlot> freeSlots;
    private List<AppointmentResponse> appointments;

    @Data
    @Builder
    public static class TimeSlot {
        private LocalTime start;
        private LocalTime end;
    }
}
