package com.medibook.domain.payment.dto;

import com.medibook.domain.payment.entity.PaymentProvider;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class InitiatePaymentRequest {

    @NotNull(message = "Appointment ID is required")
    private Long appointmentId;

    @NotNull(message = "Payment provider is required")
    private PaymentProvider provider;

    @NotNull
    @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
    private BigDecimal amount;

    private String currency = "NGN";

    private String callbackUrl;

    private String idempotencyKey;
}
