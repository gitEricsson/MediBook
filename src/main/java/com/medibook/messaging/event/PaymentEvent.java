package com.medibook.messaging.event;

import com.medibook.domain.payment.entity.PaymentProvider;
import com.medibook.domain.payment.entity.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {

    private String eventId;
    private String eventType;        // INITIATED | SUCCEEDED | FAILED | REFUNDED
    private int    schemaVersion;

    private Long            paymentId;
    private Long            appointmentId;
    private Long            patientId;
    private String          patientEmail;
    private PaymentProvider provider;
    private String          providerRef;
    private BigDecimal      amount;
    private String          currency;
    private PaymentStatus   status;
    private String          failureReason;

    private LocalDateTime occurredAt;
}
