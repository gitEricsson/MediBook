package com.medibook.domain.pricing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Singleton pricing policy persisted to MySQL so admins can RUD the hospital-wide
 * pricing knobs without a redeploy. {@code id = 1} always. The {@link com.medibook.domain.pricing.service.PricingPolicyService}
 * caches reads and writes; never instantiate or delete this row directly.
 */
@Entity
@Table(name = "pricing_policy")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PricingPolicy {

    /** Always {@code 1L} — there is exactly one policy row. */
    @Id
    private Long id;

    @Column(name = "emergency_multiplier_pct", nullable = false)
    @Builder.Default
    private int emergencyMultiplierPct = 150;

    @Column(name = "follow_up_discount_pct", nullable = false)
    @Builder.Default
    private int followUpDiscountPct = 20;

    @Column(name = "experience_premium_pct", nullable = false)
    @Builder.Default
    private int experiencePremiumPct = 20;

    @Column(name = "experience_threshold_years", nullable = false)
    @Builder.Default
    private int experienceThresholdYears = 20;

    @Column(name = "medium_surcharge_pct", nullable = false)
    @Builder.Default
    private int mediumSurchargePct = 10;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
