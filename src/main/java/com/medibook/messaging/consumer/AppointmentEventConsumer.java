package com.medibook.messaging.consumer;

import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics = KafkaTopics.APPOINTMENT_EVENTS,
            groupId = "medibook-notification-group",
            containerFactory = "appointmentKafkaListenerContainerFactory"
    )
    public void onAppointmentEvent(ConsumerRecord<String, AppointmentEvent> record,
                                   Acknowledgment ack) {
        AppointmentEvent event = record.value();
        log.info("Consumed AppointmentEvent [{}] type={}", event.getEventId(), event.getEventType());

        // Exceptions propagate to the container's DefaultErrorHandler (retry + DLT routing)
        switch (event.getEventType()) {
            case "BOOKED"    -> notificationService.sendAppointmentBooked(event);
            case "CONFIRMED" -> notificationService.sendAppointmentConfirmed(event);
            case "CANCELLED" -> notificationService.sendAppointmentCancelled(event);
            default          -> log.warn("Unhandled event type: {}", event.getEventType());
        }
        ack.acknowledge();
    }
}
