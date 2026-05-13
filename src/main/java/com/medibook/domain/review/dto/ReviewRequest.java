package com.medibook.domain.review.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ReviewRequest {

    @NotNull
    private Long appointmentId;

    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private byte rating;

    @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
    private String comment;
}
