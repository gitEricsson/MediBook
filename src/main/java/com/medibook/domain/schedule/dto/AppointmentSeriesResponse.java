package com.medibook.domain.schedule.dto;

import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.schedule.entity.AppointmentSeries;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
public class AppointmentSeriesResponse {

    private Long id;
    private Long patientId;
    private Long doctorId;
    private String doctorName;
    private String recurrenceType;
    private int recurrenceInterval;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer maxOccurrences;
    private LocalTime timeOfDay;
    private int durationMins;
    private AppointmentType appointmentType;
    private String reason;
    private String status;
    private LocalDateTime createdAt;

    public static AppointmentSeriesResponse fromEntity(AppointmentSeries s) {
        return AppointmentSeriesResponse.builder()
                .id(s.getId())
                .patientId(s.getPatient().getId())
                .doctorId(s.getDoctor().getId())
                .doctorName(s.getDoctor().getUser().getFullName())
                .recurrenceType(s.getRecurrenceType())
                .recurrenceInterval(s.getRecurrenceInterval())
                .startDate(s.getStartDate())
                .endDate(s.getEndDate())
                .maxOccurrences(s.getMaxOccurrences())
                .timeOfDay(s.getTimeOfDay())
                .durationMins(s.getDurationMins())
                .appointmentType(s.getAppointmentType())
                .reason(s.getReason())
                .status(s.getStatus())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
