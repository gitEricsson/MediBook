package com.medibook.domain.appointment.service;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.config.HospitalProperties;
import com.medibook.domain.appointment.dto.EmergencyFeeEstimateResponse;
import com.medibook.domain.appointment.dto.FeeEstimateResponse;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.pricing.entity.PricingPolicy;
import com.medibook.domain.pricing.service.PricingPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Policy-driven pricing engine.
 *
 * <p>Fee formula:
 * <ol>
 *   <li>Base fee = {@code Department.baseConsultationFee}</li>
 *   <li>Consultation-type modifier:
 *       EMERGENCY +{@code emergencyMultiplierPct}%,
 *       FOLLOW_UP -{@code followUpDiscountPct}%,
 *       FIRST_VISIT no change.</li>
 *   <li>Senior surcharge: if {@code yearsOfExperience > experienceThresholdYears} →
 *       +{@code experiencePremiumPercent}% (applied after type modifier).</li>
 *   <li>Medium surcharge: VIDEO or AUDIO → +{@code mediumSurchargePct}%
 *       (applied last).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AppointmentPricingService {

    private final HospitalProperties     props;
    private final DoctorRepository       doctorRepository;
    private final DepartmentRepository   departmentRepository;
    private final PricingPolicyService   pricingPolicyService;

    public BigDecimal computeFee(Doctor doctor, AppointmentType consultationType, ConsultationMedium medium) {
        // DB policy is authoritative; HospitalProperties yaml stays as the fallback
        // boot-time default. Read each knob through the live policy so admin edits
        // take effect on the very next call.
        PricingPolicy policy = pricingPolicyService.get();

        BigDecimal base = resolveBaseFee(doctor);

        BigDecimal fee = base;

        // 1. Consultation-type modifier
        if (consultationType == AppointmentType.EMERGENCY) {
            fee = fee.add(fee.multiply(pct(policy.getEmergencyMultiplierPct())));
        } else if (consultationType == AppointmentType.FOLLOW_UP) {
            fee = fee.subtract(fee.multiply(pct(policy.getFollowUpDiscountPct())));
        }

        // 2. Senior surcharge
        if (doctor.getYearsOfExperience() > policy.getExperienceThresholdYears()) {
            fee = fee.add(fee.multiply(pct(policy.getExperiencePremiumPct())));
        }

        // 3. Medium surcharge
        if (medium != null && medium != ConsultationMedium.PHYSICAL) {
            fee = fee.add(fee.multiply(pct(policy.getMediumSurchargePct())));
        }

        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public FeeEstimateResponse estimateFee(Long doctorId, AppointmentType consultationType, ConsultationMedium medium) {
        Doctor doctor = doctorRepository.findByIdWithDetails(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));

        PricingPolicy policy = pricingPolicyService.get();

        // Recompute each tier individually so the FE can display a transparent breakdown.
        // The math mirrors computeFee() — keep them in sync.
        BigDecimal base = resolveBaseFee(doctor);

        BigDecimal typeAdjustment = BigDecimal.ZERO;
        if (consultationType == AppointmentType.EMERGENCY) {
            typeAdjustment = base.multiply(pct(policy.getEmergencyMultiplierPct()));
        } else if (consultationType == AppointmentType.FOLLOW_UP) {
            typeAdjustment = base.multiply(pct(policy.getFollowUpDiscountPct())).negate();
        }
        BigDecimal afterType = base.add(typeAdjustment);

        BigDecimal seniorSurcharge = BigDecimal.ZERO;
        boolean seniorApplied = doctor.getYearsOfExperience() > policy.getExperienceThresholdYears();
        if (seniorApplied) {
            seniorSurcharge = afterType.multiply(pct(policy.getExperiencePremiumPct()));
        }
        BigDecimal afterSenior = afterType.add(seniorSurcharge);

        BigDecimal mediumSurcharge = BigDecimal.ZERO;
        boolean mediumApplied = medium != null && medium != ConsultationMedium.PHYSICAL;
        if (mediumApplied) {
            mediumSurcharge = afterSenior.multiply(pct(policy.getMediumSurchargePct()));
        }
        BigDecimal fee = afterSenior.add(mediumSurcharge).setScale(2, RoundingMode.HALF_UP);

        String typeLabel = switch (consultationType) {
            case FIRST_VISIT -> "First visit";
            case FOLLOW_UP   -> "Follow-up";
            case EMERGENCY   -> "Emergency";
            default          -> consultationType != null ? consultationType.name() : "Consultation";
        };
        String mediumLabel = medium == null ? "In-person" : switch (medium) {
            case PHYSICAL -> "In-person";
            case AUDIO    -> "Audio call";
            case VIDEO    -> "Video call";
        };
        String departmentName = doctor.getDepartment() != null ? doctor.getDepartment().getName() : null;

        return new FeeEstimateResponse(
                fee,
                seniorApplied,
                mediumApplied,
                base.setScale(2, RoundingMode.HALF_UP),
                typeAdjustment.setScale(2, RoundingMode.HALF_UP),
                seniorSurcharge.setScale(2, RoundingMode.HALF_UP),
                mediumSurcharge.setScale(2, RoundingMode.HALF_UP),
                typeLabel,
                mediumLabel,
                departmentName,
                "NGN"
        );
    }

    /**
     * Estimate an EMERGENCY consultation's fee before a specific doctor has been
     * assigned. The patient picks a department (optional) and medium on the
     * emergency request screen; we don't know yet whether the auto-assigned
     * doctor will be a senior consultant, so the breakdown returned here
     * assumes a non-senior doctor and exposes the potential senior premium
     * separately via {@code seniorSurchargeIfApplicable}. The FE renders it as
     * a disclaimer ("may incur an additional senior premium…") rather than as
     * a line item in the total.
     */
    @Transactional(readOnly = true)
    public EmergencyFeeEstimateResponse estimateEmergencyFee(Long departmentId, ConsultationMedium medium) {
        PricingPolicy policy = pricingPolicyService.get();

        BigDecimal base;
        String departmentName = null;
        if (departmentId != null) {
            com.medibook.domain.department.entity.Department dept = departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Department", "id", departmentId));
            BigDecimal deptFee = dept.getBaseConsultationFee();
            base = (deptFee != null && deptFee.compareTo(BigDecimal.ZERO) > 0) ? deptFee : props.getBaseFee();
            departmentName = dept.getName();
        } else {
            base = props.getBaseFee();
        }

        BigDecimal typeAdjustment = base.multiply(pct(policy.getEmergencyMultiplierPct()));
        BigDecimal afterType = base.add(typeAdjustment);

        BigDecimal mediumSurcharge = BigDecimal.ZERO;
        boolean mediumApplied = medium != null && medium != ConsultationMedium.PHYSICAL;
        if (mediumApplied) {
            mediumSurcharge = afterType.multiply(pct(policy.getMediumSurchargePct()));
        }
        BigDecimal fee = afterType.add(mediumSurcharge).setScale(2, RoundingMode.HALF_UP);

        // What the patient would pay extra IF the assigned doctor turns out
        // to be a senior consultant. Applied to (base + typeAdjustment), same
        // tier ordering as the main computeFee.
        BigDecimal seniorSurchargeIfApplicable = afterType
                .multiply(pct(policy.getExperiencePremiumPct()))
                .setScale(2, RoundingMode.HALF_UP);

        String mediumLabel = medium == null ? "In-person" : switch (medium) {
            case PHYSICAL -> "In-person";
            case AUDIO    -> "Audio call";
            case VIDEO    -> "Video call";
        };

        return new EmergencyFeeEstimateResponse(
                fee,
                mediumApplied,
                base.setScale(2, RoundingMode.HALF_UP),
                typeAdjustment.setScale(2, RoundingMode.HALF_UP),
                mediumSurcharge.setScale(2, RoundingMode.HALF_UP),
                seniorSurchargeIfApplicable,
                mediumLabel,
                departmentName,
                "NGN"
        );
    }

    private static BigDecimal pct(int percent) {
        return BigDecimal.valueOf(percent).divide(BigDecimal.valueOf(100));
    }

    /**
     * Resolve the department's base consultation fee, falling back to the hospital-wide
     * default when the department row is unset or stored as 0 (legacy data, migration
     * accident, or an admin who typed 0 by mistake). Returning 0 here would make the
     * breakdown look broken on the FE — better to surface the configured default and
     * let the admin correct the row.
     */
    private BigDecimal resolveBaseFee(Doctor doctor) {
        if (doctor.getDepartment() != null) {
            BigDecimal dept = doctor.getDepartment().getBaseConsultationFee();
            if (dept != null && dept.compareTo(BigDecimal.ZERO) > 0) {
                return dept;
            }
        }
        return props.getBaseFee();
    }
}
