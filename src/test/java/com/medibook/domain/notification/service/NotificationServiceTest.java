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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

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
    @Mock SimpMessagingTemplate           messagingTemplate;
    @Mock CacheManager                    cacheManager;
    @Mock Cache                           unreadCountCache;
    @Mock com.medibook.infrastructure.metrics.NotificationMetrics notificationMetrics;
    @Mock com.medibook.common.mail.TransactionalEmailService transactionalEmailService;

    private org.springframework.retry.support.RetryTemplate notificationRetryTemplate;
    private NotificationService notificationService;

    private AppointmentEvent event;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(cacheManager.getCache("notificationUnreadCounts")).thenReturn(unreadCountCache);
        // Use a real single-attempt RetryTemplate so the callback executes directly
        notificationRetryTemplate = new org.springframework.retry.support.RetryTemplate();
        notificationRetryTemplate.setRetryPolicy(
                new org.springframework.retry.policy.SimpleRetryPolicy(1));
        notificationService = new NotificationService(
                notificationRepository, cassandraOperations, stringRedisTemplate,
                listenerContainer, objectMapper, messagingTemplate,
                cacheManager, notificationRetryTemplate, notificationMetrics,
                transactionalEmailService);
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
    @DisplayName("save — notification is persisted to Cassandra BEFORE Redis publish (durability first)")
    void save_persistsCassandraBeforeRedisPubSub() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"type\":\"APPOINTMENT_BOOKED\"}");
        var order = inOrder(cassandraOperations, stringRedisTemplate);

        notificationService.save(1L, "Test", "Msg", "APPOINTMENT_BOOKED", 42L);

        order.verify(cassandraOperations).insert(any(Notification.class), any(InsertOptions.class));
        order.verify(stringRedisTemplate).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("save — publishes to Redis pub/sub channel for cross-instance fan-out")
    void save_publishesToRedisPubSub() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"type\":\"APPOINTMENT_BOOKED\"}");

        notificationService.save(1L, "Test", "Test message", "APPOINTMENT_BOOKED", 42L);

        verify(stringRedisTemplate).convertAndSend(eq("notifications:user:1"), anyString());
    }

    @Test
    @DisplayName("save — Redis failure does NOT lose the notification (Cassandra already written)")
    void save_redisPubSubFailure_notificationStillPersisted() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("Redis down"));
        assertThatNoException().isThrownBy(() ->
                notificationService.save(1L, "Test", "Msg", "APPOINTMENT_BOOKED", 42L));

        // Cassandra insert already happened before Redis
        verify(cassandraOperations).insert(any(Notification.class), any(InsertOptions.class));
    }

    @Test
    @DisplayName("onMessage — valid payload routes to correct user's WebSocket queue")
    void onMessage_validPayload_deliversToCorrectUser() throws Exception {
        NotificationResponse expectedPayload = NotificationResponse.builder()
                .type("APPOINTMENT_BOOKED").title("Appointment Booked").build();
        when(objectMapper.readValue(anyString(), eq(NotificationResponse.class)))
                .thenReturn(expectedPayload);

        String channel = "notifications:user:42";
        String body    = "{\"type\":\"APPOINTMENT_BOOKED\"}";
        notificationService.onMessage(
                new DefaultMessage(channel.getBytes(), body.getBytes()), null);

        verify(messagingTemplate).convertAndSendToUser(
                eq("42"),
                eq("/queue/notifications"),
                eq(expectedPayload));
    }

    @Test
    @DisplayName("onMessage — user A's notification is never delivered to user B's queue")
    void onMessage_notificationIsolation_noLeakBetweenUsers() throws Exception {
        NotificationResponse forUser42 = NotificationResponse.builder()
                .type("APPOINTMENT_BOOKED").build();
        when(objectMapper.readValue(anyString(), eq(NotificationResponse.class)))
                .thenReturn(forUser42);

        notificationService.onMessage(
                new DefaultMessage("notifications:user:42".getBytes(), "{}".getBytes()), null);

        // Must send to "42" only
        verify(messagingTemplate).convertAndSendToUser(
                eq("42"), anyString(), any(NotificationResponse.class));
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq("99"), anyString(), any());
    }

    @Test
    @DisplayName("onMessage — malformed JSON is caught, service continues (no crash)")
    void onMessage_malformedJson_logsAndContinues() throws Exception {
        when(objectMapper.readValue(anyString(), eq(NotificationResponse.class)))
                .thenThrow(new RuntimeException("Bad JSON"));

        assertThatNoException().isThrownBy(() ->
                notificationService.onMessage(
                        new DefaultMessage("notifications:user:1".getBytes(), "BADJSON".getBytes()), null));

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("onMessage — WebSocket delivery failure is silently ignored (user offline)")
    void onMessage_webSocketDeliveryFailure_doesNotThrow() throws Exception {
        NotificationResponse payload = NotificationResponse.builder().type("TEST").build();
        when(objectMapper.readValue(anyString(), eq(NotificationResponse.class)))
                .thenReturn(payload);
        doThrow(new RuntimeException("No WebSocket session"))
                .when(messagingTemplate).convertAndSendToUser(any(), any(), any());

        assertThatNoException().isThrownBy(() ->
                notificationService.onMessage(
                        new DefaultMessage("notifications:user:1".getBytes(), "{}".getBytes()), null));
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
    @DisplayName("getUnreadCount — uses findUnreadByUserId list size")
    void getUnreadCount_usesListSize() {
        var n1 = new com.medibook.domain.notification.entity.Notification();
        var n2 = new com.medibook.domain.notification.entity.Notification();
        var n3 = new com.medibook.domain.notification.entity.Notification();
        when(notificationRepository.findUnreadByUserId(1L)).thenReturn(List.of(n1, n2, n3));

        long count = notificationService.getUnreadCount(1L);

        assertThat(count).isEqualTo(3L);
        verify(notificationRepository).findUnreadByUserId(1L);
    }

    @Test
    @DisplayName("getUnreadCount - returns cached value when available")
    void getUnreadCount_returnsCachedValue() {
        when(unreadCountCache.get(1L, Long.class)).thenReturn(7L);

        long count = notificationService.getUnreadCount(1L);

        assertThat(count).isEqualTo(7L);
        verify(notificationRepository, never()).findUnreadByUserId(anyLong());
    }

    @Test
    @DisplayName("save - evicts cached unread count for the target user")
    void save_evictsUnreadCountCache() {
        notificationService.save(1L, "Test", "Test message", "APPOINTMENT_BOOKED", 42L);

        verify(unreadCountCache).evict(1L);
    }
}
