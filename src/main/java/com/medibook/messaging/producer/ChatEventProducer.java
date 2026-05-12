package com.medibook.messaging.producer;

import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.ChatEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @CircuitBreaker(name = "kafkaProducer", fallbackMethod = "publishFallback")
    public void publishChatEvent(ChatEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }

        String topic  = resolveTopicForEvent(event.getEventType());
        String key    = String.valueOf(event.getConversationId());

        kafkaTemplate.send(topic, key, event)
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ChatEvent [{}] type={}: {}",
                                event.getEventId(), event.getEventType(), ex.getMessage());
                    } else {
                        log.info("Published ChatEvent [{}] type={} topic={}",
                                event.getEventId(), event.getEventType(), topic);
                    }
                });
    }

    private String resolveTopicForEvent(String eventType) {
        if (eventType == null) return KafkaTopics.CHAT_EVENTS;
        return switch (eventType) {
            case "MEDICAL_URGENCY_FLAGGED", "DOCTOR_ESCALATION_REQUIRED" -> KafkaTopics.URGENCY_EVENTS;
            case "AI_RESPONSE_CREATED", "AI_DRAFT_CREATED", "AI_SUMMARY_CREATED" -> KafkaTopics.AI_EVENTS;
            default -> KafkaTopics.CHAT_EVENTS;
        };
    }

    private void publishFallback(ChatEvent event, Throwable t) {
        log.error("CircuitBreaker OPEN — ChatEvent dropped [{}] type={}: {}",
                event.getEventId(), event.getEventType(), t.getMessage());
    }
}
