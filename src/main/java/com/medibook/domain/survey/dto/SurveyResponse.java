package com.medibook.domain.survey.dto;

import com.medibook.domain.survey.entity.ConsultationSurvey;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Admin-only view of a submitted consultation survey. Never returned to patients. */
@Data
@Builder
public class SurveyResponse {

    private Long id;
    private Long appointmentId;
    private Long patientId;
    private Long doctorId;
    private String doctorName;
    private int overallSatisfaction;
    private int communicationQuality;
    private int waitTimeExperience;
    private int recommendLikelihood;
    private String privateComments;
    private LocalDateTime submittedAt;

    public static SurveyResponse fromEntity(ConsultationSurvey s) {
        return SurveyResponse.builder()
                .id(s.getId())
                .appointmentId(s.getAppointment().getId())
                .patientId(s.getPatient().getId())
                .doctorId(s.getDoctor().getId())
                .doctorName(s.getDoctor().getUser().getFullName())
                .overallSatisfaction(s.getOverallSatisfaction())
                .communicationQuality(s.getCommunicationQuality())
                .waitTimeExperience(s.getWaitTimeExperience())
                .recommendLikelihood(s.getRecommendLikelihood())
                .privateComments(s.getPrivateComments())
                .submittedAt(s.getSubmittedAt())
                .build();
    }
}
