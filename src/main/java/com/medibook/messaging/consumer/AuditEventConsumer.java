package com.medibook.messaging.consumer;

import com.medibook.audit.service.AuditLogService;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.AuditEvent;
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

    @KafkaListener(
            topics = KafkaTopics.AUDIT_EVENTS,
            groupId = "medibook-audit-group",
            containerFactory = "auditKafkaListenerContainerFactory"
    )
    public void onAuditEvent(ConsumerRecord<String, AuditEvent> record, Acknowledgment ack) {
        AuditEvent event = record.value();
        log.debug("Consuming AuditEvent [{}] action={}", event.getEventId(), event.getAction());
        auditLogService.persist(event);
        ack.acknowledge();
    }
}
