package com.medibook.messaging.consumer;

import com.medibook.audit.service.AuditLogService;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.AuditEvent;
import com.medibook.messaging.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventConsumer")
class AuditEventConsumerTest {

    @Mock AuditLogService auditLogService;
    @Mock ProcessedEventRepository processedEventRepository;
    @Mock Acknowledgment acknowledgment;

    AuditEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AuditEventConsumer(auditLogService, processedEventRepository);
    }

    @Test
    void newAuditEventIsPersistedAndRecorded() {
        AuditEvent event = event("audit-1", "USER_LOGIN");

        consumer.onAuditEvent(record(event), acknowledgment);

        verify(auditLogService).persist(event);
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo("audit-1");
        assertThat(captor.getValue().getEventType()).isEqualTo("USER_LOGIN");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void duplicateAuditEventIsAcknowledgedWithoutPersisting() {
        AuditEvent event = event("audit-2", "APPOINTMENT_BOOKED");
        when(processedEventRepository.existsById("audit-2")).thenReturn(true);

        consumer.onAuditEvent(record(event), acknowledgment);

        verifyNoInteractions(auditLogService);
        verify(processedEventRepository, never()).save(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void auditEventWithoutIdIsPersistedButNotRecordedAsProcessed() {
        AuditEvent event = event(null, "DOCTOR_UPDATED");

        consumer.onAuditEvent(record(event), acknowledgment);

        verify(auditLogService).persist(event);
        verify(processedEventRepository, never()).save(any());
        verify(acknowledgment).acknowledge();
    }

    private static ConsumerRecord<String, AuditEvent> record(AuditEvent event) {
        return new ConsumerRecord<>(KafkaTopics.AUDIT_EVENTS, 0, 0L, "audit", event);
    }

    private static AuditEvent event(String eventId, String action) {
        return AuditEvent.builder()
                .eventId(eventId)
                .action(action)
                .resourceType("User")
                .resourceId("42")
                .build();
    }
}
