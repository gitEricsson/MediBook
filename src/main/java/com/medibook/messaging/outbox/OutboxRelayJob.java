package com.medibook.messaging.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Transactional Outbox relay: polls PENDING outbox events and publishes them to Kafka.
 * Guarantees at-least-once delivery. Consumers must be idempotent.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelayJob {

    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository          outboxRepository;
    private final OutboxRelayService             outboxService;
    private final KafkaTemplate<String, Object>  kafkaTemplate;

    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void relay() {
        List<OutboxEvent> pending = outboxRepository.findPendingEvents(LocalDateTime.now(), BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.debug("Outbox relay: processing {} events", pending.size());

        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                outboxService.handleFailure(event, ex);
                            } else {
                                outboxService.markProcessed(event);
                                log.debug("Outbox event [{}] published to [{}]", event.getId(), event.getTopic());
                            }
                        });
            } catch (Exception ex) {
                outboxService.handleFailure(event, ex);
            }
        }
    }
}
