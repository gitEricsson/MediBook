package com.medibook.messaging.consumer;

import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.WaitlistEvent;
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
public class WaitlistEventConsumer {

    private final NotificationService notificationService;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(
            topics = KafkaTopics.WAITLIST_EVENTS,
            groupId = "medibook-waitlist-notification-group",
            containerFactory = "waitlistKafkaListenerContainerFactory"
    )
    public void onWaitlistEvent(ConsumerRecord<String, WaitlistEvent> record, Acknowledgment ack) {
        WaitlistEvent event = record.value();
        log.info("Consumed WaitlistEvent [{}] type={}", event.getEventId(), event.getEventType());

        if (event.getEventId() != null && processedEventRepository.existsById(event.getEventId())) {
            ack.acknowledge();
            return;
        }

        switch (event.getEventType()) {
            case "PROMOTED" -> notificationService.sendWaitlistPromoted(
                    event.getPatientId(), event.getAppointmentId(), event.getDoctorName(), event.getScheduledAt());
            
            case "JOINED" -> notificationService.sendWaitlistJoined(
                    event.getPatientId(), event.getDoctorName());

            default -> log.debug("No specific notification action for waitlist event: {}", event.getEventType());
        }

        if (event.getEventId() != null) {
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.getEventId())
                    .eventType("WAITLIST_" + event.getEventType())
                    .build());
        }
        ack.acknowledge();
    }
}
