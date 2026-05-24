package com.medibook.domain.doctor.dto;

import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;

@Data
@Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class WorkingHoursResponse {

    private Long id;
    private Integer dayOfWeek;
    private String dayName;
    private LocalTime startTime;
    private LocalTime endTime;

    public static WorkingHoursResponse fromEntity(DoctorWorkingHours h) {
        return WorkingHoursResponse.builder()
                .id(h.getId())
                .dayOfWeek(h.getDayOfWeek())
                .dayName(dayName(h.getDayOfWeek()))
                .startTime(h.getStartTime())
                .endTime(h.getEndTime())
                .build();
    }

    private static String dayName(int dayOfWeek) {
        return switch (dayOfWeek) {
            case 1 -> "Monday";
            case 2 -> "Tuesday";
            case 3 -> "Wednesday";
            case 4 -> "Thursday";
            case 5 -> "Friday";
            case 6 -> "Saturday";
            case 7 -> "Sunday";
            default -> "Unknown";
        };
    }
}
