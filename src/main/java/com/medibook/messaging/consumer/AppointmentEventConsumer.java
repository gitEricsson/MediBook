package com.medibook.messaging.consumer;

import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.domain.notification.service.NotificationService;
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
            topics = KafkaTopics.APPOINTMENT_EVENTS,
            groupId = "medibook-notification-group",
            containerFactory = "appointmentKafkaListenerContainerFactory"
    )
    public void onAppointmentEvent(ConsumerRecord<String, AppointmentEvent> record,
                                   Acknowledgment ack) {
        try {
            AppointmentEvent event = record.value();
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
                default -> log.warn("Unhandled event type: {}", event.getEventType());
            }
            if (event.getEventId() != null) {
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(event.getEventId())
                        .eventType(event.getEventType())
                        .build());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process appointment event", e);
        }
    }
}
