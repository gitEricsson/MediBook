package com.medibook.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.event.AuditEvent;
import com.medibook.messaging.outbox.OutboxEvent;
import com.medibook.messaging.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentEventProducer")
class AppointmentEventProducerTest {

    @Mock OutboxEventRepository outboxRepository;

    AppointmentEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new AppointmentEventProducer(outboxRepository, new ObjectMapper().registerModule(new JavaTimeModule()));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void appointmentEventGetsEventMetadataAndIsEnqueuedToOutbox() {
        AppointmentEvent event = AppointmentEvent.builder()
                .appointmentId(123L)
                .eventType("BOOKED")
                .build();

        producer.publishAppointmentEvent(event);

        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getOccurredAt()).isNotNull();
        OutboxEvent outboxEvent = captureSavedOutboxEvent();
        assertThat(outboxEvent.getAggregateType()).isEqualTo("APPOINTMENT");
        assertThat(outboxEvent.getAggregateId()).isEqualTo("123");
        assertThat(outboxEvent.getEventType()).isEqualTo("BOOKED");
        assertThat(outboxEvent.getTopic()).isEqualTo(KafkaTopics.APPOINTMENT_EVENTS);
        assertThat(outboxEvent.getPayload()).contains("\"appointmentId\":123");
    }

    @Test
    void existingAppointmentEventMetadataIsPreserved() {
        LocalDateTime occurredAt = LocalDateTime.parse("2026-05-10T10:15:30");
        AppointmentEvent event = AppointmentEvent.builder()
                .appointmentId(124L)
                .eventId("existing-id")
                .eventType("CONFIRMED")
                .occurredAt(occurredAt)
                .build();

        producer.publishAppointmentEvent(event);

        assertThat(event.getEventId()).isEqualTo("existing-id");
        assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
        OutboxEvent outboxEvent = captureSavedOutboxEvent();
        assertThat(outboxEvent.getAggregateId()).isEqualTo("124");
        assertThat(outboxEvent.getPayload()).contains("\"eventId\":\"existing-id\"");
    }

    @Test
    void auditEventGetsEventMetadataAndIsEnqueuedToOutbox() {
        AuditEvent event = AuditEvent.builder()
                .resourceType("Appointment")
                .resourceId("321")
                .action("APPOINTMENT_BOOKED")
                .build();

        producer.publishAuditEvent(event);

        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getOccurredAt()).isNotNull();
        OutboxEvent outboxEvent = captureSavedOutboxEvent();
        assertThat(outboxEvent.getAggregateType()).isEqualTo("Appointment");
        assertThat(outboxEvent.getAggregateId()).isEqualTo("321");
        assertThat(outboxEvent.getEventType()).isEqualTo("AUDIT_APPOINTMENT_BOOKED");
        assertThat(outboxEvent.getTopic()).isEqualTo(KafkaTopics.AUDIT_EVENTS);
        assertThat(outboxEvent.getPayload()).contains("\"resourceId\":\"321\"");
    }

    private OutboxEvent captureSavedOutboxEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue();
    }
}
