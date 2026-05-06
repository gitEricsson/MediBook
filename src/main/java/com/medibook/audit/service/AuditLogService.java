package com.medibook.audit.service;

import com.medibook.audit.entity.AuditLog;
import com.medibook.audit.repository.AuditLogRepository;
import com.medibook.messaging.event.AuditEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @CircuitBreaker(name = "cassandraWrite", fallbackMethod = "persistFallback")
    public void persist(AuditEvent event) {
        AuditLog logEntry = AuditLog.builder()
                .actorId(event.getActorId())
                .occurredAt(event.getOccurredAt() != null ? event.getOccurredAt().toInstant(ZoneOffset.UTC) : Instant.now())
                .eventId(UUID.fromString(event.getEventId()))
                .action(event.getAction())
                .actorEmail(event.getActorEmail())
                .entityType(event.getResourceType())
                .entityId(event.getResourceId())
                .ipAddress(null) // To be populated from request context in future
                .correlationId(null)
                .build();

        auditLogRepository.save(logEntry);
    }

    public List<AuditLog> getRecentByActor(Long actorId) {
        return auditLogRepository.findRecentByActorId(actorId);
    }

    private void persistFallback(AuditEvent event, Throwable t) {
        log.error("Cassandra CB OPEN — AuditLog dropped for actor [{}]: {}", event.getActorId(), t.getMessage());
    }
}
