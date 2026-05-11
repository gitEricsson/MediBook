package com.medibook.domain.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorUtilizationResponse {

    private List<DoctorStat> doctors;

    @Data @Builder @AllArgsConstructor @NoArgsConstructor
    public static class DoctorStat {
        private Long doctorId;
        private String doctorName;
        private String specialization;
        private long totalAppointments;
        private long completedAppointments;
        private long cancelledAppointments;
        private double utilizationPercent;
        private double averageRating;
    }
}
