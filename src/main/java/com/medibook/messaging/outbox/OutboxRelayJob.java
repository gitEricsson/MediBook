package com.medibook.messaging.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Transactional Outbox relay: polls PENDING outbox events and publishes them to Kafka.
 * Guarantees at-least-once delivery. Consumers must be idempotent.
 * Includes error handling with retry logic for critical job.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelayJob {

    private static final int BATCH_SIZE = 50;

    private final OutboxRelayService             outboxService;
    private final KafkaTemplate<String, Object>  kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    @SchedulerLock(name = "OutboxRelayJob_relay", lockAtMostFor = "2m", lockAtLeastFor = "1s")
    public void relay() {
        try {
            relayImpl();
        } catch (Exception ex) {
            log.error("OutboxRelayJob failed with error", ex);
            meterRegistry.counter("scheduled.job.failure", "job", "OutboxRelayJob").increment();
            // Retry once after 5 seconds for critical job
            try {
                Thread.sleep(5_000);
                relayImpl();
                log.info("OutboxRelayJob retry succeeded");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("OutboxRelayJob retry interrupted", ie);
            } catch (Exception retryEx) {
                log.error("OutboxRelayJob retry failed", retryEx);
                meterRegistry.counter("scheduled.job.failure", "job", "OutboxRelayJob").increment();
            }
        }
    }

    private void relayImpl() {
        // Find-pending + mark-processing must run inside a JPA transaction (the underlying
        // @Modifying update needs one). Delegating to OutboxRelayService keeps the @Scheduled
        // entry point lean and the persistence work transactional.
        List<OutboxEvent> pending = outboxService.claimPending(BATCH_SIZE);
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
