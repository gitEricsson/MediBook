package com.medibook.domain.payment.service;

import com.medibook.config.HospitalProperties;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.pricing.entity.PricingPolicy;
import com.medibook.domain.pricing.service.PricingPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for consultation pricing.
 *
 * Resolution order:
 *  1. Base fee  — department.baseConsultationFee if set, else specialization map, else hospital default.
 *  2. Consultation-type modifier — FOLLOW_UP gets a discount, EMERGENCY gets a surcharge.
 *  3. Senior-doctor surcharge — applied when doctor.yearsOfExperience > threshold.
 *  4. Medium surcharge — VIDEO/AUDIO incur a telemedicine platform fee.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingEngine {

    // Audio is treated as half-rate vs video by default. The DB policy stores a
    // single mediumSurchargePct that we apply to video; audio receives half.
    private static final BigDecimal AUDIO_FRACTION_OF_MEDIUM = BigDecimal.valueOf(0.5);

    private final HospitalProperties     hospitalProperties;
    private final PricingPolicyService   pricingPolicyService;

    /**
     * Calculate the canonical fee for a consultation.
     *
     * @param doctor           the assigned doctor
     * @param consultationType FIRST_VISIT | FOLLOW_UP | EMERGENCY
     * @param medium           PHYSICAL | AUDIO | VIDEO
     * @return the final fee to charge
     */
    public BigDecimal calculate(Doctor doctor,
                                AppointmentType consultationType,
                                ConsultationMedium medium) {

        PricingPolicy policy = pricingPolicyService.get();

        BigDecimal base = resolveBase(doctor);
        BigDecimal afterType = applyTypeModifier(base, consultationType, policy);
        BigDecimal afterSenior = applySeniorSurcharge(afterType, doctor, policy);
        BigDecimal total = applyMediumSurcharge(afterSenior, medium, policy);

        log.debug("Pricing: doctor={} type={} medium={} | base={} → typeAdj={} → senior={} → final={}",
                doctor.getId(), consultationType, medium, base, afterType, afterSenior, total);

        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveBase(Doctor doctor) {
        // Department-level override takes precedence over specialization map.
        if (doctor.getDepartment() != null) {
            BigDecimal deptFee = doctor.getDepartment().getBaseConsultationFee();
            if (deptFee != null && deptFee.compareTo(BigDecimal.ZERO) > 0) {
                return deptFee;
            }
        }
        return hospitalProperties.getBaseFeeForSpecialization(doctor.getSpecialization());
    }

    private BigDecimal applyTypeModifier(BigDecimal base, AppointmentType type, PricingPolicy policy) {
        if (type == null) return base;
        return switch (type) {
            case FOLLOW_UP  -> base.subtract(base.multiply(pct(policy.getFollowUpDiscountPct())));
            case EMERGENCY  -> base.add(base.multiply(pct(policy.getEmergencyMultiplierPct())));
            default         -> base; // FIRST_VISIT, IN_PERSON, TELEHEALTH, TELEMEDICINE
        };
    }

    private BigDecimal applySeniorSurcharge(BigDecimal fee, Doctor doctor, PricingPolicy policy) {
        if (doctor.getYearsOfExperience() > policy.getExperienceThresholdYears()) {
            return fee.add(fee.multiply(pct(policy.getExperiencePremiumPct())));
        }
        return fee;
    }

    private BigDecimal applyMediumSurcharge(BigDecimal fee, ConsultationMedium medium, PricingPolicy policy) {
        if (medium == null) return fee;
        BigDecimal videoPct = pct(policy.getMediumSurchargePct());
        BigDecimal surcharge = switch (medium) {
            case VIDEO -> fee.multiply(videoPct);
            case AUDIO -> fee.multiply(videoPct).multiply(AUDIO_FRACTION_OF_MEDIUM);
            default    -> BigDecimal.ZERO;
        };
        return fee.add(surcharge);
    }

    private static BigDecimal pct(int percent) {
        return BigDecimal.valueOf(percent).divide(BigDecimal.valueOf(100));
    }
}
