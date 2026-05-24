package com.medibook.messaging.consumer;

import com.medibook.common.exception.TemporaryFailureException;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.AppointmentEvent;
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
public class AppointmentEventConsumer {

    private final NotificationService notificationService;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(
            topics = {KafkaTopics.APPOINTMENT_EVENTS, KafkaTopics.OUTSTANDING_BALANCE_EVENTS},
            groupId = "medibook-notification-group",
            containerFactory = "appointmentKafkaListenerContainerFactory"
    )
    public void onAppointmentEvent(ConsumerRecord<String, Object> record,
                                   Acknowledgment ack) {
        try {
            // Guard against stale messages serialized as raw Strings from a previous deployment.
            if (!(record.value() instanceof AppointmentEvent)) {
                log.warn("Skipping non-AppointmentEvent message on {}: valueType={}",
                        KafkaTopics.APPOINTMENT_EVENTS,
                        record.value() == null ? "null" : record.value().getClass().getSimpleName());
                ack.acknowledge();
                return;
            }
            AppointmentEvent event = (AppointmentEvent) record.value();
            log.info("Consumed AppointmentEvent [{}] type={}", event.getEventId(), event.getEventType());
            if (event.getEventId() != null && processedEventRepository.existsById(event.getEventId())) {
                log.info("Skipping duplicate AppointmentEvent [{}]", event.getEventId());
                ack.acknowledge();
                return;
            }

            switch (event.getEventType()) {
                case "BOOKED" -> notificationService.sendAppointmentBooked(event);
                case "CONFIRMED", "STATUS_CHANGED_TO_CONFIRMED" -> notificationService.sendAppointmentConfirmed(event);
                case "CANCELLED", "STATUS_CHANGED_TO_CANCELLED" -> notificationService.sendAppointmentCancelled(event);
                case "REMINDER" -> notificationService.sendAppointmentReminder(event);
                case "EMERGENCY_CONSULTATION_REQUESTED" -> notificationService.sendEmergencyConsultationRequested(event);
                case "OUTSTANDING_BALANCE_CREATED" -> notificationService.sendOutstandingBalanceCreated(event);
                default -> log.warn("Unhandled event type: {}", event.getEventType());
            }
            if (event.getEventId() != null) {
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(event.getEventId())
                        .eventType(event.getEventType())
                        .build());
            }
            ack.acknowledge();
        } catch (TemporaryFailureException e) {
            // Kafka will retry this message (don't acknowledge)
            log.warn("Transient notification failure, message will be retried: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to process appointment event", e);
            // Acknowledge to avoid infinite retry loop on unrecoverable errors.
            ack.acknowledge();
        }
    }
}
