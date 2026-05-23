package com.medibook.domain.pricing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Patch payload for the pricing policy. All fields optional — null fields are ignored,
 * non-null fields replace the current value. Each percentage is bounded to a sane range
 * so a typo can't 100x a fee.
 */
@Data
public class PricingPolicyRequest {

    @Min(value = 0,   message = "Emergency surcharge cannot be negative")
    @Max(value = 500, message = "Emergency surcharge cannot exceed 500%")
    private Integer emergencyMultiplierPct;

    @Min(value = 0,   message = "Follow-up discount cannot be negative")
    @Max(value = 100, message = "Follow-up discount cannot exceed 100%")
    private Integer followUpDiscountPct;

    @Min(value = 0,   message = "Senior premium cannot be negative")
    @Max(value = 200, message = "Senior premium cannot exceed 200%")
    private Integer experiencePremiumPct;

    @Min(value = 0,   message = "Seniority threshold cannot be negative")
    @Max(value = 60,  message = "Seniority threshold cannot exceed 60 years")
    private Integer experienceThresholdYears;

    @Min(value = 0,   message = "Medium surcharge cannot be negative")
    @Max(value = 100, message = "Medium surcharge cannot exceed 100%")
    private Integer mediumSurchargePct;

    /**
     * Optimistic lock: the {@code updatedAt} returned by the latest GET. The PATCH is
     * rejected if a concurrent admin saved newer values.
     */
    @NotNull(message = "ifUnchangedSince is required for optimistic concurrency")
    private java.time.Instant ifUnchangedSince;
}
