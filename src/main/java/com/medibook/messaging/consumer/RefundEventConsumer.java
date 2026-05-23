package com.medibook.messaging.consumer;

import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.service.AppointmentTransitionService;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.domain.payment.service.PaymentService;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.PaymentEvent;
import com.medibook.messaging.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Drives the cancellation-refund saga.
 *
 * Handles two event types on the {@code refund.events} topic:
 *
 *  CANCELLATION_REFUND_REQUESTED — patient cancelled an appointment that was
 *    already paid; we call the gateway (executeSystemRefund), then flip the
 *    appointment to REFUNDED, then notify the patient.
 *
 *  REFUND_INITIATED — admin-triggered refund from PaymentService.refundPayment;
 *    gateway call already happened there, so we just flip + notify.
 *
 * Both paths are fully idempotent via ProcessedEventRepository dedup.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RefundEventConsumer {

    private final PaymentService              paymentService;
    private final AppointmentTransitionService transitionService;
    private final NotificationService         notificationService;
    private final ProcessedEventRepository    processedEventRepository;

    @KafkaListener(
            topics = KafkaTopics.REFUND_EVENTS,
            groupId = "medibook-refund-saga-group",
            containerFactory = "paymentKafkaListenerContainerFactory"
    )
    public void onRefundEvent(ConsumerRecord<String, PaymentEvent> record, Acknowledgment ack) {
        try {
            PaymentEvent event = record.value();
            if (event == null) {
                ack.acknowledge();
                return;
            }
            log.info("[RefundSaga] Consumed event [{}] type={}", event.getEventId(), event.getEventType());

            // Dedup
            if (event.getEventId() != null && processedEventRepository.existsById(event.getEventId())) {
                log.info("[RefundSaga] Skipping duplicate event [{}]", event.getEventId());
                ack.acknowledge();
                return;
            }

            switch (event.getEventType()) {
                case "CANCELLATION_REFUND_REQUESTED" -> handleCancellationRefundRequested(event);
                case "REFUND_INITIATED"              -> handleRefundInitiated(event);
                default -> log.debug("[RefundSaga] Unhandled refund event type: {}", event.getEventType());
            }

            if (event.getEventId() != null) {
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(event.getEventId())
                        .eventType("REFUND_" + event.getEventType())
                        .build());
            }
            ack.acknowledge();

        } catch (Exception ex) {
            log.error("[RefundSaga] Failed to process refund event from partition={} offset={}",
                    record.partition(), record.offset(), ex);
            // Do not ack — Kafka will redeliver. DLQ configured in container factory.
        }
    }

    private void handleCancellationRefundRequested(PaymentEvent event) {
        // 1. Call gateway and mark payment REFUNDED
        paymentService.executeSystemRefund(event.getPaymentId(), "Appointment cancellation — full refund");

        // 2. Transition appointment CANCELLED → REFUNDED (idempotent guard inside systemTransition)
        transitionAppointmentToRefunded(event);

        // 3. Notify patient
        notifyRefund(event);
    }

    private void handleRefundInitiated(PaymentEvent event) {
        // Gateway call already made by PaymentService.refundPayment — just transition + notify.
        transitionAppointmentToRefunded(event);
        notifyRefund(event);
    }

    private void transitionAppointmentToRefunded(PaymentEvent event) {
        if (event.getAppointmentId() == null) return;
        try {
            transitionService.systemTransition(
                    event.getAppointmentId(),
                    AppointmentStatus.REFUNDED,
                    "Refund processed via " + event.getEventType());
        } catch (Exception ex) {
            // Log but don't fail the saga — appointment may already be REFUNDED.
            log.warn("[RefundSaga] Could not transition appointment [{}] to REFUNDED: {}",
                    event.getAppointmentId(), ex.getMessage());
        }
    }

    private void notifyRefund(PaymentEvent event) {
        if (event.getPatientId() == null) return;
        try {
            notificationService.sendRefundIssued(
                    event.getPatientId(),
                    event.getAmount() != null ? event.getAmount().toPlainString() : "0",
                    event.getCurrency() != null ? event.getCurrency() : "NGN");
        } catch (Exception ex) {
            log.warn("[RefundSaga] Refund notification failed for patient [{}]: {}",
                    event.getPatientId(), ex.getMessage());
        }
    }
}
