package com.medibook.messaging.producer;

import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.event.AuditEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @CircuitBreaker(name = "kafkaProducer", fallbackMethod = "publishFallback")
    public void publishAppointmentEvent(AppointmentEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }

        kafkaTemplate.send(KafkaTopics.APPOINTMENT_EVENTS,
                String.valueOf(event.getAppointmentId()), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish AppointmentEvent [{}]: {}",
                                event.getEventId(), ex.getMessage());
                    } else {
                        log.info("Published AppointmentEvent [{}] type={} offset={}",
                                event.getEventId(), event.getEventType(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    @CircuitBreaker(name = "kafkaProducer", fallbackMethod = "auditFallback")
    public void publishAuditEvent(AuditEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }
        kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS,
                event.getResourceType() + ":" + event.getResourceId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish AuditEvent [{}]: {}", event.getEventId(), ex.getMessage());
                    }
                });
    }


    private void publishFallback(AppointmentEvent event, Throwable t) {
        log.error("CircuitBreaker OPEN — AppointmentEvent dropped [{}]: {}", event.getEventId(), t.getMessage());
    }

    private void auditFallback(AuditEvent event, Throwable t) {
        log.error("CircuitBreaker OPEN — AuditEvent dropped [{}]: {}", event.getEventId(), t.getMessage());
    }
}
