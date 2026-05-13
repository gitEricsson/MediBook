package com.medibook.messaging.consumer;

import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.TelemedicineEvent;
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
public class TelemedicineEventConsumer {

    private final NotificationService notificationService;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(
            topics = KafkaTopics.TELEMEDICINE_EVENTS,
            groupId = "medibook-telemedicine-notification-group",
            containerFactory = "telemedicineKafkaListenerContainerFactory"
    )
    public void onTelemedicineEvent(ConsumerRecord<String, TelemedicineEvent> record, Acknowledgment ack) {
        TelemedicineEvent event = record.value();
        log.info("Consumed TelemedicineEvent [{}] type={}", event.getEventId(), event.getEventType());

        if (event.getEventId() != null && processedEventRepository.existsById(event.getEventId())) {
            ack.acknowledge();
            return;
        }

        switch (event.getEventType()) {
            case "READY" -> notificationService.sendTelemedicineSessionReady(
                    event.getPatientId(), event.getDoctorId(), event.getAppointmentId());
            
            case "WAITING" -> notificationService.sendTelemedicinePatientWaiting(
                    event.getDoctorId(), event.getAppointmentId());
            
            default -> log.debug("No specific notification action for telemedicine event: {}", event.getEventType());
        }

        if (event.getEventId() != null) {
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.getEventId())
                    .eventType("TELEMEDICINE_" + event.getEventType())
                    .build());
        }
        ack.acknowledge();
    }
}
