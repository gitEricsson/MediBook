package com.medibook.domain.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.notification.dto.NotificationResponse;
import com.medibook.domain.notification.entity.Notification;
import com.medibook.domain.notification.repository.NotificationRepository;
import com.medibook.messaging.event.AppointmentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService — Unit Tests")
class NotificationServiceTest {

    @Mock NotificationRepository          notificationRepository;
    @Mock CassandraOperations             cassandraOperations;
    @Mock StringRedisTemplate             stringRedisTemplate;
    @Mock RedisMessageListenerContainer   listenerContainer;
    @Mock ObjectMapper                    objectMapper;

    @InjectMocks NotificationService notificationService;

    private AppointmentEvent event;

    @BeforeEach
    void setUp() {
        event = AppointmentEvent.builder()
                .appointmentId(100L)
                .patientId(1L).patientName("Alice Patient").patientEmail("alice@test.com")
                .doctorId(2L).doctorName("Dr. Bob Smith").doctorEmail("bob@test.com")
                .scheduledAt(LocalDateTime.now().plusDays(3))
                .build();
    }

    @Test
    @DisplayName("sendAppointmentBooked — inserts one notification for patient and one for doctor")
    void sendAppointmentBooked_savesPatientAndDoctorNotifications() {
        notificationService.sendAppointmentBooked(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(cassandraOperations, times(2)).insert(captor.capture(), any(InsertOptions.class));
        List<Notification> saved = captor.getAllValues();
        assertThat(saved).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(saved).extracting(Notification::getType)
                .containsOnly("APPOINTMENT_BOOKED");
        assertThat(saved).extracting(Notification::getAppointmentId)
                .containsOnly(100L);
    }

    @Test
    @DisplayName("sendAppointmentBooked — both notifications have read=false")
    void sendAppointmentBooked_notificationsAreUnread() {
        notificationService.sendAppointmentBooked(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(cassandraOperations, times(2)).insert(captor.capture(), any(InsertOptions.class));
        assertThat(captor.getAllValues()).extracting(Notification::isRead)
                .containsOnly(false);
    }

    @Test
    @DisplayName("sendAppointmentConfirmed — inserts exactly one notification for the patient only")
    void sendAppointmentConfirmed_savesOnlyPatientNotification() {
        notificationService.sendAppointmentConfirmed(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(cassandraOperations, times(1)).insert(captor.capture(), any(InsertOptions.class));
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getType()).isEqualTo("APPOINTMENT_CONFIRMED");
    }

    @Test
    @DisplayName("sendAppointmentCancelled — inserts one notification for patient and one for doctor")
    void sendAppointmentCancelled_savesPatientAndDoctorNotifications() {
        notificationService.sendAppointmentCancelled(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(cassandraOperations, times(2)).insert(captor.capture(), any(InsertOptions.class));
        assertThat(captor.getAllValues()).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(captor.getAllValues()).extracting(Notification::getType)
                .containsOnly("APPOINTMENT_CANCELLED");
    }

    @Test
    @DisplayName("getRecent — delegates to repository and maps to NotificationResponse")
    void getRecent_delegatesToRepository() {
        Notification n = Notification.builder().userId(1L).type("APPOINTMENT_BOOKED").build();
        when(notificationRepository.findRecentByUserId(1L)).thenReturn(List.of(n));

        List<NotificationResponse> result = notificationService.getRecent(1L);

        assertThat(result).hasSize(1);
        verify(notificationRepository).findRecentByUserId(1L);
    }

    @Test
    @DisplayName("getUnread — delegates to repository and maps to NotificationResponse")
    void getUnread_delegatesToRepository() {
        when(notificationRepository.findUnreadByUserId(1L)).thenReturn(List.of());

        List<NotificationResponse> result = notificationService.getUnread(1L);

        assertThat(result).isEmpty();
        verify(notificationRepository).findUnreadByUserId(1L);
    }

    @Test
    @DisplayName("getUnreadCount — uses COUNT query, not full list load")
    void getUnreadCount_usesCountQuery() {
        when(notificationRepository.countUnreadByUserId(1L)).thenReturn(5L);

        long count = notificationService.getUnreadCount(1L);

        assertThat(count).isEqualTo(5L);
        verify(notificationRepository).countUnreadByUserId(1L);
        verify(notificationRepository, never()).findUnreadByUserId(anyLong());
    }

    @Test
    @DisplayName("save — inserts with 30-day TTL so rows self-expire")
    void save_insertsWithTtl() {
        notificationService.save(1L, "Test", "Test message", "APPOINTMENT_BOOKED", 42L);

        ArgumentCaptor<InsertOptions> optionsCaptor = ArgumentCaptor.forClass(InsertOptions.class);
        verify(cassandraOperations).insert(any(Notification.class), optionsCaptor.capture());
        InsertOptions options = optionsCaptor.getValue();
        assertThat(options.getTtl()).isNotNull();
        assertThat(options.getTtl().toDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("save — publishes to Redis pub/sub channel for cross-instance fan-out")
    void save_publishesToRedisPubSub() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"type\":\"APPOINTMENT_BOOKED\"}");

        notificationService.save(1L, "Test", "Test message", "APPOINTMENT_BOOKED", 42L);

        verify(stringRedisTemplate).convertAndSend(eq("notifications:user:1"), anyString());
    }
}
