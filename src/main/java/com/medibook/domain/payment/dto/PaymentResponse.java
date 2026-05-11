package com.medibook.domain.payment.dto;

import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.entity.PaymentProvider;
import com.medibook.domain.payment.entity.PaymentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PaymentResponse {

    private Long id;
    private Long appointmentId;
    private Long patientId;
    private String idempotencyKey;
    private PaymentProvider provider;
    private String providerRef;
    private String authorizationUrl;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private BigDecimal refundAmount;
    private LocalDateTime refundedAt;
    private LocalDateTime createdAt;

    public static PaymentResponse fromEntity(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId())
                .appointmentId(p.getAppointment().getId())
                .patientId(p.getPatient().getId())
                .idempotencyKey(p.getIdempotencyKey())
                .provider(p.getProvider())
                .providerRef(p.getProviderRef())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .status(p.getStatus())
                .refundAmount(p.getRefundAmount())
                .refundedAt(p.getRefundedAt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
