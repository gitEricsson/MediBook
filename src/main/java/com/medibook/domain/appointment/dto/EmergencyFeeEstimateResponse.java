package com.medibook.domain.appointment.dto;

import java.math.BigDecimal;

/**
 * Response for the emergency consultation fee-preview endpoint.
 *
 * <p>The patient has not been assigned a doctor yet, so the breakdown assumes
 * a non-senior consultant. {@link #seniorSurchargeIfApplicable} is the extra
 * amount that would be added on top of {@link #fee} if the auto-assigned
 * doctor turns out to be a senior consultant — surfaced as a disclaimer in
 * the UI rather than rolled into the total.
 */
public record EmergencyFeeEstimateResponse(
        BigDecimal fee,
        boolean mediumSurchargeApplied,
        BigDecimal baseFee,
        BigDecimal emergencySurcharge,
        BigDecimal mediumSurcharge,
        BigDecimal seniorSurchargeIfApplicable,
        String mediumLabel,
        String departmentName,
        String currency
) {}
