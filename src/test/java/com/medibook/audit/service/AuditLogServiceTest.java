package com.medibook.audit.service;

import com.medibook.audit.entity.AuditLog;
import com.medibook.audit.repository.AuditLogRepository;
import com.medibook.messaging.event.AuditEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService — Unit Tests")
class AuditLogServiceTest {

    @Mock AuditLogRepository auditLogRepository;

    @InjectMocks AuditLogService auditLogService;

    private static final Long   ACTOR_ID = 42L;
    private static final String EVENT_ID = UUID.randomUUID().toString();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }


    @Test
    @DisplayName("persist — maps all AuditEvent fields to AuditLog correctly")
    void persist_mapsAllEventFieldsToLogEntry() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 6, 1, 12, 0, 0);
        AuditEvent event = buildEvent(EVENT_ID, "APPOINTMENT_BOOKED", ACTOR_ID,
                "alice@test.com", "Appointment", "100", occurredAt);

        auditLogService.persist(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(saved.getEventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(saved.getAction()).isEqualTo("APPOINTMENT_BOOKED");
        assertThat(saved.getActorEmail()).isEqualTo("alice@test.com");
        assertThat(saved.getEntityType()).isEqualTo("Appointment");
        assertThat(saved.getEntityId()).isEqualTo("100");
        assertThat(saved.getOccurredAt()).isEqualTo(occurredAt.toInstant(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("persist — provided occurredAt converted to UTC Instant exactly")
    void persist_providedOccurredAt_convertedToUtcInstant() {
        LocalDateTime specificTime = LocalDateTime.of(2026, 3, 15, 9, 30, 0);
        AuditEvent event = buildEvent(EVENT_ID, "X", ACTOR_ID, null, "T", "1", specificTime);

        auditLogService.persist(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOccurredAt())
                .isEqualTo(specificTime.toInstant(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("persist — null occurredAt falls back to Instant.now() at persist time")
    void persist_nullOccurredAt_usesCurrentInstant() {
        AuditEvent event = buildEvent(EVENT_ID, "X", ACTOR_ID, null, "T", "1", null);

        Instant before = Instant.now();
        auditLogService.persist(event);
        Instant after = Instant.now();

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        Instant saved = captor.getValue().getOccurredAt();
        assertThat(saved).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("persist — eventId string is parsed to UUID and stored")
    void persist_eventIdParsedToUuid() {
        String knownId = "a8098c1a-f86e-11da-bd1a-00112444be1e";
        AuditEvent event = buildEvent(knownId, "X", ACTOR_ID, null, "T", "1", null);

        auditLogService.persist(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(UUID.fromString(knownId));
    }


    @Test
    @DisplayName("persist — no request context → ipAddress stored as null")
    void persist_noRequestContext_ipAddressIsNull() {
        // RequestContextHolder has nothing set — simulates background thread / async context
        AuditEvent event = buildEvent(EVENT_ID, "X", ACTOR_ID, null, "T", "1", null);

        auditLogService.persist(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isNull();
    }

    @Test
    @DisplayName("persist — X-Forwarded-For header → first IP extracted (trims whitespace)")
    void persist_xForwardedForHeader_firstIpExtracted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "  203.0.113.1 , 10.0.0.1, 172.16.0.5  ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuditEvent event = buildEvent(EVENT_ID, "X", ACTOR_ID, null, "T", "1", null);

        auditLogService.persist(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.1");
    }

    @Test
    @DisplayName("persist — no X-Forwarded-For → falls back to remoteAddr")
    void persist_noForwardedHeader_usesRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuditEvent event = buildEvent(EVENT_ID, "X", ACTOR_ID, null, "T", "1", null);

        auditLogService.persist(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("192.168.1.100");
    }

    @Test
    @DisplayName("persist — blank X-Forwarded-For → falls back to remoteAddr")
    void persist_blankForwardedHeader_usesRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "   ");
        request.setRemoteAddr("10.10.10.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuditEvent event = buildEvent(EVENT_ID, "X", ACTOR_ID, null, "T", "1", null);

        auditLogService.persist(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("10.10.10.10");
    }


    @Test
    @DisplayName("getRecentByActor — delegates to repository and returns result")
    void getRecentByActor_delegatesToRepository() {
        AuditLog entry = AuditLog.builder().actorId(ACTOR_ID).action("LOGIN").build();
        when(auditLogRepository.findRecentByActorId(ACTOR_ID)).thenReturn(List.of(entry));

        List<AuditLog> result = auditLogService.getRecentByActor(ACTOR_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAction()).isEqualTo("LOGIN");
        verify(auditLogRepository).findRecentByActorId(ACTOR_ID);
    }

    @Test
    @DisplayName("getRecentByActor — empty repository result returns empty list")
    void getRecentByActor_noEntries_returnsEmptyList() {
        when(auditLogRepository.findRecentByActorId(99L)).thenReturn(List.of());

        assertThat(auditLogService.getRecentByActor(99L)).isEmpty();
    }


    private AuditEvent buildEvent(String eventId, String action, Long actorId,
                                   String actorEmail, String resourceType,
                                   String resourceId, LocalDateTime occurredAt) {
        return AuditEvent.builder()
                .eventId(eventId).action(action).actorId(actorId)
                .actorEmail(actorEmail).resourceType(resourceType)
                .resourceId(resourceId).occurredAt(occurredAt)
                .build();
    }
}
