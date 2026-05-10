package com.medibook.messaging.consumer;

import com.medibook.domain.notification.service.NotificationService;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.entity.ProcessedEvent;
import com.medibook.messaging.event.AppointmentEvent;
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
@DisplayName("AppointmentEventConsumer")
class AppointmentEventConsumerTest {

    @Mock NotificationService notificationService;
    @Mock ProcessedEventRepository processedEventRepository;
    @Mock Acknowledgment acknowledgment;

    AppointmentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AppointmentEventConsumer(notificationService, processedEventRepository);
    }

    @Test
    void bookedEventSendsBookedNotificationsAndRecordsEvent() {
        AppointmentEvent event = event("event-1", "BOOKED");

        consumer.onAppointmentEvent(record(event), acknowledgment);

        verify(notificationService).sendAppointmentBooked(event);
        verifySavedEvent("event-1", "BOOKED");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void confirmedAliasesSendConfirmedNotification() {
        AppointmentEvent event = event("event-2", "STATUS_CHANGED_TO_CONFIRMED");

        consumer.onAppointmentEvent(record(event), acknowledgment);

        verify(notificationService).sendAppointmentConfirmed(event);
        verifySavedEvent("event-2", "STATUS_CHANGED_TO_CONFIRMED");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void cancelledAliasesSendCancelledNotification() {
        AppointmentEvent event = event("event-3", "STATUS_CHANGED_TO_CANCELLED");

        consumer.onAppointmentEvent(record(event), acknowledgment);

        verify(notificationService).sendAppointmentCancelled(event);
        verifySavedEvent("event-3", "STATUS_CHANGED_TO_CANCELLED");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void reminderEventSendsReminderNotification() {
        AppointmentEvent event = event("event-4", "REMINDER");

        consumer.onAppointmentEvent(record(event), acknowledgment);

        verify(notificationService).sendAppointmentReminder(event);
        verifySavedEvent("event-4", "REMINDER");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void unknownEventIsAcknowledgedAndRecordedWithoutNotification() {
        AppointmentEvent event = event("event-5", "SOMETHING_ELSE");

        consumer.onAppointmentEvent(record(event), acknowledgment);

        verifyNoInteractions(notificationService);
        verifySavedEvent("event-5", "SOMETHING_ELSE");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void duplicateEventIsAcknowledgedWithoutSideEffects() {
        AppointmentEvent event = event("event-6", "BOOKED");
        when(processedEventRepository.existsById("event-6")).thenReturn(true);

        consumer.onAppointmentEvent(record(event), acknowledgment);

        verifyNoInteractions(notificationService);
        verify(processedEventRepository, never()).save(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void eventWithoutIdIsProcessedButNotPersistedAsProcessed() {
        AppointmentEvent event = event(null, "CONFIRMED");

        consumer.onAppointmentEvent(record(event), acknowledgment);

        verify(notificationService).sendAppointmentConfirmed(event);
        verify(processedEventRepository, never()).save(any());
        verify(acknowledgment).acknowledge();
    }

    private void verifySavedEvent(String eventId, String eventType) {
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
        assertThat(captor.getValue().getEventType()).isEqualTo(eventType);
    }

    private static ConsumerRecord<String, AppointmentEvent> record(AppointmentEvent event) {
        return new ConsumerRecord<>(KafkaTopics.APPOINTMENT_EVENTS, 0, 0L, "appointment", event);
    }

    private static AppointmentEvent event(String eventId, String eventType) {
        return AppointmentEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .appointmentId(10L)
                .patientId(20L)
                .doctorId(30L)
                .build();
    }
}
