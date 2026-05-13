package com.medibook.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.messaging.outbox.OutboxEvent;
import com.medibook.messaging.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventProducer {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper          objectMapper;

    public void publish(String aggregateType, String aggregateId, String eventType, String topic, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .topic(topic)
                    .payload(payload)
                    .build());
            
            log.debug("Enqueued event [{}] to outbox for topic={}", eventType, topic);
        } catch (Exception ex) {
            log.error("Failed to serialize event [{}] for topic={}: {}", eventType, topic, ex.getMessage());
            throw new RuntimeException("Failed to enqueue event", ex);
        }
    }
}
