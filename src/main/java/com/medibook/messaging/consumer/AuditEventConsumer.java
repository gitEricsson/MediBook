package com.medibook.messaging.consumer;

import com.medibook.audit.service.AuditLogService;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.AuditEvent;
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
public class AuditEventConsumer {

    private final AuditLogService auditLogService;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(
            topics = KafkaTopics.AUDIT_EVENTS,
            groupId = "medibook-audit-group",
            containerFactory = "auditKafkaListenerContainerFactory"
    )
    public void onAuditEvent(ConsumerRecord<String, AuditEvent> record, Acknowledgment ack) {
        AuditEvent event = record.value();
        log.debug("Consuming AuditEvent [{}] action={}", event.getEventId(), event.getAction());
        if (event.getEventId() != null && processedEventRepository.existsById(event.getEventId())) {
            log.info("Skipping duplicate AuditEvent [{}]", event.getEventId());
            ack.acknowledge();
            return;
        }
        auditLogService.persist(event);
        if (event.getEventId() != null) {
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.getEventId())
                    .eventType(event.getAction())
                    .build());
        }
        ack.acknowledge();
    }
}
