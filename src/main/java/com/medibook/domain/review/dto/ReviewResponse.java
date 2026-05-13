package com.medibook.domain.review.dto;

import com.medibook.domain.review.entity.DoctorReview;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ReviewResponse {

    private Long id;
    private Long appointmentId;
    private Long patientId;
    private String patientName;
    private Long doctorId;
    private String doctorName;
    private int rating;
    private String comment;
    private String status;
    private LocalDateTime createdAt;

    public static ReviewResponse fromEntity(DoctorReview r) {
        return ReviewResponse.builder()
                .id(r.getId())
                .appointmentId(r.getAppointment().getId())
                .patientId(r.getPatient().getId())
                .patientName(r.getPatient().getFullName())
                .doctorId(r.getDoctor().getId())
                .doctorName(r.getDoctor().getUser().getFullName())
                .rating(r.getRating())
                .comment(r.getComment())
                .status(r.getStatus())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
