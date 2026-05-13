package com.medibook.messaging.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxEventRepository outboxRepository;

    private static final int MAX_RETRY_COUNT  = 5;
    private static final long RETRY_BACKOFF_S = 30;

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
