package com.medibook.domain.pricing.dto;

import com.medibook.domain.pricing.entity.PricingPolicy;

import java.time.Instant;

public record PricingPolicyResponse(
        int emergencyMultiplierPct,
        int followUpDiscountPct,
        int experiencePremiumPct,
        int experienceThresholdYears,
        int mediumSurchargePct,
        Instant updatedAt,
        Long updatedBy
) {
    public static PricingPolicyResponse fromEntity(PricingPolicy p) {
        return new PricingPolicyResponse(
                p.getEmergencyMultiplierPct(),
                p.getFollowUpDiscountPct(),
                p.getExperiencePremiumPct(),
                p.getExperienceThresholdYears(),
                p.getMediumSurchargePct(),
                p.getUpdatedAt(),
                p.getUpdatedBy()
        );
    }
}
