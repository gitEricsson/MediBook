package com.medibook.messaging.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxEventRepository outboxRepository;

    private static final int MAX_RETRY_COUNT  = 5;
    private static final long RETRY_BACKOFF_S = 30;

    /**
     * Atomically claim a batch of PENDING events by flipping them to PROCESSING.
     * Wrapped in a transaction here (rather than in the scheduled job) because the
     * underlying {@code @Modifying} query requires an active JPA transaction.
     */
    @Transactional
    public List<OutboxEvent> claimPending(int batchSize) {
        List<OutboxEvent> pending = outboxRepository.findPendingEvents(LocalDateTime.now(), batchSize);
        if (pending.isEmpty()) return pending;
        outboxRepository.markProcessing(pending.stream().map(OutboxEvent::getId).toList());
        return pending;
    }

    @Transactional
    public void markProcessed(OutboxEvent event) {
        event.setStatus("PROCESSED");
        event.setProcessedAt(LocalDateTime.now());
        outboxRepository.save(event);
    }

    @Transactional
    public void handleFailure(OutboxEvent event, Throwable ex) {
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
