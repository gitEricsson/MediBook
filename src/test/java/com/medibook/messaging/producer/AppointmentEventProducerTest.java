package com.medibook.messaging.producer;

import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.event.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentEventProducer")
class AppointmentEventProducerTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    AppointmentEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new AppointmentEventProducer(kafkaTemplate);
    }

    @Test
    void appointmentEventGetsEventMetadataAndStableKafkaKey() {
        AppointmentEvent event = AppointmentEvent.builder()
                .appointmentId(123L)
                .eventType("BOOKED")
                .build();
        when(kafkaTemplate.send(eq(KafkaTopics.APPOINTMENT_EVENTS), eq("123"), eq(event)))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());

        producer.publishAppointmentEvent(event);

        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getOccurredAt()).isNotNull();
        verify(kafkaTemplate).send(KafkaTopics.APPOINTMENT_EVENTS, "123", event);
    }

    @Test
    void existingAppointmentEventMetadataIsPreserved() {
        AppointmentEvent event = AppointmentEvent.builder()
                .appointmentId(124L)
                .eventId("existing-id")
                .eventType("CONFIRMED")
                .occurredAt(java.time.LocalDateTime.parse("2026-05-10T10:15:30"))
                .build();
        when(kafkaTemplate.send(eq(KafkaTopics.APPOINTMENT_EVENTS), eq("124"), eq(event)))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());

        producer.publishAppointmentEvent(event);

        assertThat(event.getEventId()).isEqualTo("existing-id");
        assertThat(event.getOccurredAt()).isEqualTo(java.time.LocalDateTime.parse("2026-05-10T10:15:30"));
        verify(kafkaTemplate).send(KafkaTopics.APPOINTMENT_EVENTS, "124", event);
    }

    @Test
    void auditEventGetsEventMetadataAndResourceKafkaKey() {
        AuditEvent event = AuditEvent.builder()
                .resourceType("Appointment")
                .resourceId("321")
                .action("APPOINTMENT_BOOKED")
                .build();
        when(kafkaTemplate.send(eq(KafkaTopics.AUDIT_EVENTS), eq("Appointment:321"), eq(event)))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());

        producer.publishAuditEvent(event);

        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getOccurredAt()).isNotNull();
        verify(kafkaTemplate).send(KafkaTopics.AUDIT_EVENTS, "Appointment:321", event);
    }
}
