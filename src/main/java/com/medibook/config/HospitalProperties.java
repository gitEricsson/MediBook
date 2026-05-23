package com.medibook.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hospital-wide pricing configuration.
 *
 * <p>Fee resolution rules:
 * <ol>
 *   <li>If a doctor's specialization has an explicit entry in {@code specializationFees},
 *       that value is used as the base fee.</li>
 *   <li>Otherwise the hospital-wide {@code consultationFee} is used as the base fee.</li>
 *   <li>A senior premium ({@code experiencePremiumPercent}) is layered on top when the
 *       doctor's years of experience exceeds {@code experienceThresholdYears}.</li>
 * </ol>
 *
 * <p>Specialization keys are matched case-insensitively after trimming. Configure via
 * {@code app.hospital.specialization-fees} in application yaml, e.g.:
 *
 * <pre>
 * app:
 *   hospital:
 *     consultation-fee: 5000
 *     specialization-fees:
 *       Cardiology: 15000
 *       Neurology: 18000
 *       Dermatology: 8000
 *       Pediatrics: 7000
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.hospital")
public class HospitalProperties {

    /** Default consultation fee for any specialization not explicitly mapped. */
    private BigDecimal consultationFee = BigDecimal.valueOf(5000.00);

    /** Premium percentage added for senior consultants (applied on top of any base fee). */
    private int experiencePremiumPercent = 20;

    /** Years of experience threshold to qualify as senior consultant (exclusive). */
    private int experienceThresholdYears = 20;

    /** Percentage added to the base fee for EMERGENCY consultations (e.g. 150 → 2.5× base). */
    private int emergencyMultiplierPct = 150;

    /** Percentage discount applied to base fee for FOLLOW_UP consultations (e.g. 20 → 0.8× base). */
    private int followUpDiscountPct = 20;

    /** Percentage surcharge added when consultation medium is AUDIO or VIDEO. */
    private int mediumSurchargePct = 10;

    /** Per-specialization base fees. Keys are normalized to lower-case at lookup time. */
    private Map<String, BigDecimal> specializationFees = new HashMap<>();

    public BigDecimal getBaseFee() {
        return consultationFee;
    }

    /**
     * Resolve the base fee for a specialization, falling back to the hospital-wide default.
     *
     * <p>Match order:
     * <ol>
     *   <li>Exact match on normalized key</li>
     *   <li>Substring match — e.g. "Interventional Cardiology" matches "Cardiology"</li>
     *   <li>Default {@code consultationFee}</li>
     * </ol>
     * Longer keys are preferred so "Internal Medicine" wins over a hypothetical "Medicine".
     */
    public BigDecimal getBaseFeeForSpecialization(String specialization) {
        if (specialization == null || specialization.isBlank()) {
            return consultationFee;
        }
        String norm = specialization.trim().toLowerCase(Locale.ROOT);
        // Map keys are bound case-as-written by Spring (its @ConfigurationProperties
        // binder accesses the map field directly and bypasses our setter), so we
        // normalize on every lookup instead. Cost is negligible — handful of entries.
        return specializationFees.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .filter(e -> {
                    String k = e.getKey().trim().toLowerCase(Locale.ROOT);
                    return norm.equals(k) || norm.contains(k);
                })
                // Prefer the longest matching key so "internal medicine" wins over "medicine".
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(consultationFee);
    }

    /** Apply the senior premium on top of any base fee. */
    public BigDecimal applySeniorPremium(BigDecimal baseFee) {
        BigDecimal premium = baseFee.multiply(
                BigDecimal.valueOf(experiencePremiumPercent).divide(BigDecimal.valueOf(100)));
        return baseFee.add(premium);
    }

    /**
     * Specialization-classified fee resolver. All doctors sharing a specialization charge
     * the same booking fee — years of experience no longer modifies the price. The
     * {@code yearsOfExperience} arg is kept for ABI stability and ignored.
     */
    public BigDecimal getFeeForDoctor(String specialization, int yearsOfExperience) {
        return getBaseFeeForSpecialization(specialization);
    }

    /**
     * Backwards-compatible overload. Prefer {@link #getFeeForDoctor(String, int)} so
     * specialization is honored.
     */
    public BigDecimal getFeeForDoctor(int yearsOfExperience) {
        return getFeeForDoctor(null, yearsOfExperience);
    }

    /** Senior fee using the hospital-wide default base (no specialization context). */
    public BigDecimal getSeniorFee() {
        return applySeniorPremium(consultationFee);
    }

    public boolean isSeniorConsultant(int yearsOfExperience) {
        return yearsOfExperience > experienceThresholdYears;
    }

    /** Normalize keys to lower-case so config-side casing doesn't matter at lookup. */
    public void setSpecializationFees(Map<String, BigDecimal> specializationFees) {
        Map<String, BigDecimal> normalized = new HashMap<>();
        if (specializationFees != null) {
            specializationFees.forEach((k, v) -> {
                if (k != null) normalized.put(k.trim().toLowerCase(Locale.ROOT), v);
            });
        }
        this.specializationFees = normalized;
    }
}
