package com.medibook.domain.survey.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SurveyRequest {

    @NotNull(message = "appointmentId is required")
    private Long appointmentId;

    @Min(1) @Max(5)
    @NotNull
    private Integer overallSatisfaction;

    @Min(1) @Max(5)
    @NotNull
    private Integer communicationQuality;

    @Min(1) @Max(5)
    @NotNull
    private Integer waitTimeExperience;

    @Min(1) @Max(5)
    @NotNull
    private Integer recommendLikelihood;

    @Size(max = 2000)
    private String privateComments;
}
