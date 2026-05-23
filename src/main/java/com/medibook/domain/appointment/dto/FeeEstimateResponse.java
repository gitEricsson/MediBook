package com.medibook.domain.appointment.dto;

import java.math.BigDecimal;

/**
 * Response for the fee-estimate endpoint.
 *
 * <p>Returns both the total fee and the individual line items that produced it
 * so the booking review screen can render a transparent breakdown
 * (department base + consultation-type adjustment + senior surcharge + medium surcharge).
 *
 * <p>Amounts in {@link #seniorSurcharge} and {@link #mediumSurcharge} are the
 * absolute monetary deltas added to the fee (already computed against the
 * intermediate subtotal that each tier applies to). {@link #consultationTypeAdjustment}
 * is signed: negative for FOLLOW_UP discount, positive for EMERGENCY surcharge.
 */
public record FeeEstimateResponse(
        BigDecimal fee,
        boolean seniorSurchargeApplied,
        boolean mediumSurchargeApplied,
        BigDecimal baseFee,
        BigDecimal consultationTypeAdjustment,
        BigDecimal seniorSurcharge,
        BigDecimal mediumSurcharge,
        String consultationTypeLabel,
        String mediumLabel,
        String departmentName,
        String currency
) {}
