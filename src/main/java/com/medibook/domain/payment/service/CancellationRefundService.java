package com.medibook.domain.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.entity.PaymentStatus;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.PaymentEvent;
import com.medibook.messaging.outbox.OutboxEvent;
import com.medibook.messaging.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Writes a CANCELLATION_REFUND_REQUESTED outbox event for any successful payment
 * linked to the cancelled appointment.  The actual gateway call and the
 * appointment → REFUNDED transition are handled asynchronously by RefundEventConsumer
 * so cancel() never blocks on the payment gateway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancellationRefundService {

    private final PaymentRepository     paymentRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper          objectMapper;

    /**
     * Publishes a CANCELLATION_REFUND_REQUESTED outbox event if a SUCCESSFUL payment
     * exists for the given appointment.
     *
     * @return true  if a refund was scheduled
     *         false if no paid payment was found (free slot or unpaid — nothing to refund)
     */
    @Transactional
    public boolean scheduleRefundIfPaid(Long appointmentId) {
        return paymentRepository
                .findFirstByAppointmentIdAndStatusInOrderByCreatedAtDesc(
                        appointmentId, List.of(PaymentStatus.SUCCESSFUL))
                .map(payment -> {
                    publishCancellationRefundRequested(payment);
                    log.info("[CancellationRefund] Scheduled refund for payment [{}] (appointment [{}])",
                            payment.getId(), appointmentId);
                    return true;
                })
                .orElseGet(() -> {
                    log.debug("[CancellationRefund] No successful payment found for appointment [{}] — nothing to refund",
                            appointmentId);
                    return false;
                });
    }

    private void publishCancellationRefundRequested(Payment payment) {
        // Deterministic eventId so a re-run after a crash doesn't double-publish.
        String eventId = "cancel-refund-" + payment.getId();

        PaymentEvent event = PaymentEvent.builder()
                .eventId(eventId)
                .eventType("CANCELLATION_REFUND_REQUESTED")
                .schemaVersion(1)
                .paymentId(payment.getId())
                .appointmentId(payment.getAppointment().getId())
                .patientId(payment.getPatient().getId())
                .patientEmail(payment.getPatient().getEmail())
                .provider(payment.getProvider())
                .providerRef(payment.getProviderRef())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .occurredAt(LocalDateTime.now())
                .build();

        try {
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateType("Payment")
                    .aggregateId(String.valueOf(payment.getId()))
                    .eventType("CANCELLATION_REFUND_REQUESTED")
                    .topic(KafkaTopics.REFUND_EVENTS)
                    .payload(objectMapper.writeValueAsString(event))
                    .build());
        } catch (JsonProcessingException e) {
            log.error("[CancellationRefund] Failed to serialize CANCELLATION_REFUND_REQUESTED for payment [{}]",
                    payment.getId(), e);
            throw new RuntimeException("Failed to serialize cancellation refund event", e);
        }
    }
}
