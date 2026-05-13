package com.medibook.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.ChatEvent;
import com.medibook.messaging.outbox.OutboxEvent;
import com.medibook.messaging.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventProducer {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper          objectMapper;

    public void publishChatEvent(ChatEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }

        String topic = resolveTopicForEvent(event.getEventType());

        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateType("CHAT")
                    .aggregateId(String.valueOf(event.getConversationId()))
                    .eventType(event.getEventType())
                    .topic(topic)
                    .payload(payload)
                    .build());
            
            log.debug("Enqueued ChatEvent [{}] to outbox for topic={}", event.getEventId(), topic);
        } catch (Exception ex) {
            log.error("Failed to serialize ChatEvent [{}]: {}", event.getEventId(), ex.getMessage());
            throw new RuntimeException("Failed to enqueue chat event", ex);
        }
    }

    private String resolveTopicForEvent(String eventType) {
        if (eventType == null) return KafkaTopics.CHAT_EVENTS;
        return switch (eventType) {
            case "MEDICAL_URGENCY_FLAGGED", "DOCTOR_ESCALATION_REQUIRED" -> KafkaTopics.URGENCY_EVENTS;
            case "AI_RESPONSE_CREATED", "AI_DRAFT_CREATED", "AI_SUMMARY_CREATED" -> KafkaTopics.AI_EVENTS;
            default -> KafkaTopics.CHAT_EVENTS;
        };
    }
}
