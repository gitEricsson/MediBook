package com.medibook.domain.payment.provider;

import com.medibook.domain.payment.entity.PaymentProvider;

import java.math.BigDecimal;

public interface PaymentProviderPort {

    PaymentProvider getProvider();

    InitiateResult initiatePayment(InitiateRequest request);

    VerifyResult verifyPayment(String providerRef);

    RefundResult refundPayment(String providerRef, BigDecimal amount, String reason);

    boolean verifyWebhookSignature(String payload, String signature);

    record InitiateRequest(
            String idempotencyKey,
            String customerEmail,
            String customerName,
            BigDecimal amount,
            String currency,
            String description,
            String callbackUrl
    ) {}

    record InitiateResult(
            String providerRef,
            String authorizationUrl,
            String status
    ) {}

    record VerifyResult(
            String providerRef,
            String status,
            BigDecimal amountPaid,
            String currency
    ) {}

    record RefundResult(
            String refundRef,
            boolean success,
            String message
    ) {}
}
