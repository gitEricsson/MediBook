package com.medibook.messaging.consumer;

import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.ChatEvent;
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
public class ChatEventConsumer {

    private final NotificationService notificationService;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(
            topics = {KafkaTopics.CHAT_EVENTS, KafkaTopics.URGENCY_EVENTS, KafkaTopics.AI_EVENTS},
            groupId = "medibook-chat-notification-group",
            containerFactory = "chatKafkaListenerContainerFactory"
    )
    public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
        ChatEvent event = record.value();
        log.info("Consumed ChatEvent [{}] type={} topic={}", 
                event.getEventId(), event.getEventType(), record.topic());

        if (event.getEventId() != null && processedEventRepository.existsById(event.getEventId())) {
            log.info("Skipping duplicate ChatEvent [{}]", event.getEventId());
            ack.acknowledge();
            return;
        }

        switch (event.getEventType()) {
            case "MEDICAL_URGENCY_FLAGGED" -> notificationService.sendUrgencyAlert(
                    event.getDoctorId(), event.getConversationId(), event.getUrgencyKeywords());
            
            case "AI_RESPONSE_CREATED" -> log.debug("AI response notification logic could go here");
            
            case "DOCTOR_ESCALATION_REQUIRED" -> notificationService.sendChatEscalationRequired(
                    event.getDoctorId(), event.getAppointmentId());
            
            default -> log.debug("No notification action for chat event type: {}", event.getEventType());
        }

        if (event.getEventId() != null) {
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.getEventId())
                    .eventType("CHAT_" + event.getEventType())
                    .build());
        }
        ack.acknowledge();
    }
}
