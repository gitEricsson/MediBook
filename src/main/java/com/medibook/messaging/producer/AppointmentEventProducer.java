package com.medibook.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.event.AuditEvent;
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
public class AppointmentEventProducer {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper          objectMapper;

    public void publishAppointmentEvent(AppointmentEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }

        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateType("APPOINTMENT")
                    .aggregateId(String.valueOf(event.getAppointmentId()))
                    .eventType(event.getEventType())
                    .topic(KafkaTopics.APPOINTMENT_EVENTS)
                    .payload(payload)
                    .build());
            
            log.debug("Enqueued AppointmentEvent [{}] to outbox", event.getEventId());
        } catch (Exception ex) {
            log.error("Failed to serialize AppointmentEvent [{}]: {}", event.getEventId(), ex.getMessage());
            // In a production scenario, we might want to throw a custom exception here
            // to roll back the business transaction if the event cannot be enqueued.
            throw new RuntimeException("Failed to enqueue appointment event", ex);
        }
    }

    public void publishAuditEvent(AuditEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }

        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateType(event.getResourceType())
                    .aggregateId(event.getResourceId())
                    .eventType("AUDIT_" + event.getAction())
                    .topic(KafkaTopics.AUDIT_EVENTS)
                    .payload(payload)
                    .build());
            
            log.debug("Enqueued AuditEvent [{}] to outbox", event.getEventId());
        } catch (Exception ex) {
            log.error("Failed to serialize AuditEvent [{}]: {}", event.getEventId(), ex.getMessage());
            throw new RuntimeException("Failed to enqueue audit event", ex);
        }
    }
}
