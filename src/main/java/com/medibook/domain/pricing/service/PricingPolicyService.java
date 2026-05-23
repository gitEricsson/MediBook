package com.medibook.domain.pricing.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.pricing.dto.PricingPolicyRequest;
import com.medibook.domain.pricing.entity.PricingPolicy;
import com.medibook.domain.pricing.repository.PricingPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Read/write surface for the hospital-wide pricing policy.
 *
 * <p>The singleton row ({@code id=1}) is seeded by V42 with values mirroring the
 * old {@code HospitalProperties} yaml defaults. {@link AppointmentPricingService}
 * reads through this service instead of binding to yaml, so admin edits take
 * effect immediately without a redeploy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingPolicyService {

    private static final long SINGLETON_ID = 1L;

    private final PricingPolicyRepository repository;

    @Transactional(readOnly = true)
    public PricingPolicy get() {
        // The V42 migration seeds row id=1, but in test contexts that bypass Flyway
        // we lazily insert defaults so the service is always non-null.
        return repository.findById(SINGLETON_ID).orElseGet(() -> {
            PricingPolicy seed = PricingPolicy.builder()
                    .id(SINGLETON_ID)
                    .updatedAt(Instant.now())
                    .build();
            return repository.save(seed);
        });
    }

    @Transactional
    public PricingPolicy update(PricingPolicyRequest req, Long actorId) {
        PricingPolicy policy = get();
        // Optimistic concurrency: reject the write if the policy changed between
        // when the admin opened the form and when they submitted it.
        if (policy.getUpdatedAt() != null && req.getIfUnchangedSince() != null
                && policy.getUpdatedAt().isAfter(req.getIfUnchangedSince())) {
            throw new MediBookException(
                    "Pricing policy was modified by another admin. Reload and retry.",
                    HttpStatus.CONFLICT, "POLICY_STALE");
        }
        if (req.getEmergencyMultiplierPct() != null)    policy.setEmergencyMultiplierPct(req.getEmergencyMultiplierPct());
        if (req.getFollowUpDiscountPct() != null)       policy.setFollowUpDiscountPct(req.getFollowUpDiscountPct());
        if (req.getExperiencePremiumPct() != null)      policy.setExperiencePremiumPct(req.getExperiencePremiumPct());
        if (req.getExperienceThresholdYears() != null)  policy.setExperienceThresholdYears(req.getExperienceThresholdYears());
        if (req.getMediumSurchargePct() != null)        policy.setMediumSurchargePct(req.getMediumSurchargePct());
        policy.setUpdatedAt(Instant.now());
        policy.setUpdatedBy(actorId);
        PricingPolicy saved = repository.save(policy);
        log.info("Pricing policy updated by user [{}]: emerg={}, follow={}, seniorPct={}, seniorThreshold={}, medium={}",
                actorId,
                saved.getEmergencyMultiplierPct(), saved.getFollowUpDiscountPct(),
                saved.getExperiencePremiumPct(), saved.getExperienceThresholdYears(),
                saved.getMediumSurchargePct());
        return saved;
    }
}
