package com.medibook.audit.service;

import com.medibook.audit.entity.AuditLog;
import com.medibook.audit.repository.AuditLogRepository;
import com.medibook.common.web.CorrelationIdFilter;
import com.medibook.messaging.event.AuditEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
                .ipAddress(resolveClientIp())
                .correlationId(MDC.get(CorrelationIdFilter.MDC_KEY))
                .build();

        auditLogRepository.save(logEntry);
    }

    public List<AuditLog> getRecentByActor(Long actorId) {
        return auditLogRepository.findRecentByActorId(actorId);
    }

    private void persistFallback(AuditEvent event, Throwable t) {
        log.error("Cassandra CB OPEN — AuditLog dropped for actor [{}]: {}", event.getActorId(), t.getMessage());
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
