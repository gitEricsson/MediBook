package com.medibook.messaging.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class OutboxRelayJob {

    private static final int BATCH_SIZE       = 50;
    private static final int MAX_RETRY_COUNT  = 5;
    private static final long RETRY_BACKOFF_S = 30;

    private final OutboxEventRepository          outboxRepository;
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
                                handleFailure(event, ex);
                            } else {
                                markProcessed(event);
                                log.debug("Outbox event [{}] published to [{}]", event.getId(), event.getTopic());
                            }
                        });
            } catch (Exception ex) {
                handleFailure(event, ex);
            }
        }
    }

    @Transactional
    protected void markProcessed(OutboxEvent event) {
        event.setStatus("PROCESSED");
        event.setProcessedAt(LocalDateTime.now());
        outboxRepository.save(event);
    }

    @Transactional
    protected void handleFailure(OutboxEvent event, Throwable ex) {
        log.warn("Outbox event [{}] failed to publish: {}", event.getId(), ex.getMessage());
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(ex.getMessage());

        if (event.getRetryCount() >= MAX_RETRY_COUNT) {
            event.setStatus("FAILED");
            log.error("Outbox event [{}] permanently failed after {} retries", event.getId(), MAX_RETRY_COUNT);
        } else {
            event.setStatus("PENDING");
            event.setScheduledAfter(LocalDateTime.now().plusSeconds(RETRY_BACKOFF_S * event.getRetryCount()));
        }
        outboxRepository.save(event);
    }
}
