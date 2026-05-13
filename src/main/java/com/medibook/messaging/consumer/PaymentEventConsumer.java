package com.medibook.messaging.consumer;

import com.medibook.domain.notification.service.NotificationService;
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

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final NotificationService       notificationService;
    private final ProcessedEventRepository  processedEventRepository;

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_EVENTS,
            groupId = "medibook-payment-notification-group",
            containerFactory = "paymentKafkaListenerContainerFactory"
    )
    public void onPaymentEvent(ConsumerRecord<String, PaymentEvent> record, Acknowledgment ack) {
        try {
            PaymentEvent event = record.value();
            log.info("Consumed PaymentEvent [{}] type={}", event.getEventId(), event.getEventType());

            if (event.getEventId() != null && processedEventRepository.existsById(event.getEventId())) {
                log.info("Skipping duplicate PaymentEvent [{}]", event.getEventId());
                ack.acknowledge();
                return;
            }

            switch (event.getEventType()) {
                case "SUCCEEDED" -> notificationService.sendPaymentSucceeded(
                        event.getPatientId(),
                        event.getProviderRef(),
                        event.getAmount() != null ? event.getAmount().toPlainString() : "0",
                        event.getCurrency() != null ? event.getCurrency() : "NGN");

                case "FAILED" -> notificationService.sendPaymentFailed(
                        event.getPatientId(), event.getProviderRef());

                case "REFUNDED" -> notificationService.sendRefundIssued(
                        event.getPatientId(),
                        event.getAmount() != null ? event.getAmount().toPlainString() : "0",
                        event.getCurrency() != null ? event.getCurrency() : "NGN");

                default -> log.debug("Unhandled payment event type: {}", event.getEventType());
            }

            if (event.getEventId() != null) {
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(event.getEventId())
                        .eventType("PAYMENT_" + event.getEventType())
                        .build());
            }
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Failed to process payment event", ex);
        }
    }
}
