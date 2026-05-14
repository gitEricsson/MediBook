package com.medibook.infrastructure.notification;

import com.medibook.common.exception.TemporaryFailureException;
import com.medibook.domain.notification.entity.Notification;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.infrastructure.metrics.NotificationMetrics;
import com.medibook.messaging.event.AppointmentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.cache.CacheManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.notification.repository.NotificationRepository;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationRetryTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private CassandraOperations cassandraOperations;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisMessageListenerContainer listenerContainer;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private NotificationMetrics notificationMetrics;

    private RetryTemplate retryTemplate;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        retryTemplate = new RetryConfig().notificationRetryTemplate();
        notificationService = new NotificationService(
            notificationRepository,
            cassandraOperations,
            stringRedisTemplate,
            listenerContainer,
            objectMapper,
            messagingTemplate,
            cacheManager,
            retryTemplate,
            notificationMetrics
        );
    }

    @Test
    void testRetryOnTransientFailure() {
        // Arrange
        AppointmentEvent event = createMockAppointmentEvent();

        // Mock CassandraOperations to fail once, then succeed
        doThrow(new RuntimeException("Transient timeout"))
            .doReturn(null)
            .when(cassandraOperations).insert(any(Notification.class), any());

        doReturn(0L).when(stringRedisTemplate).convertAndSend(anyString(), anyString());

        // Act & Assert
        assertDoesNotThrow(() -> notificationService.sendAppointmentBooked(event));

        // Attempt 1: patient insert fails (1 call) → retry
        // Attempt 2: patient insert succeeds + doctor insert succeeds (2 calls)
        verify(cassandraOperations, times(3)).insert(any(Notification.class), any());
        verify(notificationMetrics, atLeastOnce()).recordSuccess();
    }

    @Test
    void testNoRetryOnPermanentFailure() {
        // Arrange
        AppointmentEvent event = createMockAppointmentEvent();

        // Mock CassandraOperations to throw permanent error
        doThrow(new IllegalArgumentException("Invalid appointment ID"))
            .when(cassandraOperations).insert(any(Notification.class), any());

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
            () -> notificationService.sendAppointmentBooked(event));

        // Verify cassandraOperations was called only once (no retry)
        verify(cassandraOperations, times(1)).insert(any(Notification.class), any());
        verify(notificationMetrics).recordPermanentFailure();
    }

    @Test
    void testExhaustedRetries() {
        // Arrange
        AppointmentEvent event = createMockAppointmentEvent();

        // Mock CassandraOperations to always fail with transient error
        doThrow(new RuntimeException("Always times out"))
            .when(cassandraOperations).insert(any(Notification.class), any());

        // Act & Assert
        assertThrows(TemporaryFailureException.class,
            () -> notificationService.sendAppointmentBooked(event));

        // 3 attempts, each only reaches the first save(patient) before failing
        verify(cassandraOperations, times(3)).insert(any(Notification.class), any());
        verify(notificationMetrics, atLeast(2)).recordRetry();
    }

    @Test
    void testAppointmentConfirmedRetry() {
        // Arrange
        AppointmentEvent event = createMockAppointmentEvent();

        // Mock to fail once, then succeed
        doThrow(new RuntimeException("Network error"))
            .doReturn(null)
            .when(cassandraOperations).insert(any(Notification.class), any());

        doReturn(0L).when(stringRedisTemplate).convertAndSend(anyString(), anyString());

        // Act & Assert
        assertDoesNotThrow(() -> notificationService.sendAppointmentConfirmed(event));

        // Verify success was recorded after retry
        verify(notificationMetrics).recordSuccess();
    }

    @Test
    void testAppointmentCancelledRetry() {
        // Arrange
        AppointmentEvent event = createMockAppointmentEvent();

        // Mock to fail once, then succeed
        doThrow(new RuntimeException("Socket timeout"))
            .doReturn(null)
            .when(cassandraOperations).insert(any(Notification.class), any());

        doReturn(0L).when(stringRedisTemplate).convertAndSend(anyString(), anyString());

        // Act & Assert
        assertDoesNotThrow(() -> notificationService.sendAppointmentCancelled(event));

        // Attempt 1: patient insert fails (1 call) → retry
        // Attempt 2: patient insert succeeds + doctor insert succeeds (2 calls)
        verify(cassandraOperations, times(3)).insert(any(Notification.class), any());
        verify(notificationMetrics, atLeastOnce()).recordSuccess();
    }

    @Test
    void testAppointmentReminderRetry() {
        // Arrange
        AppointmentEvent event = createMockAppointmentEvent();

        // Mock to fail once, then succeed
        doThrow(new RuntimeException("IO error"))
            .doReturn(null)
            .when(cassandraOperations).insert(any(Notification.class), any());

        doReturn(0L).when(stringRedisTemplate).convertAndSend(anyString(), anyString());

        // Act & Assert
        assertDoesNotThrow(() -> notificationService.sendAppointmentReminder(event));

        // Verify success was recorded
        verify(notificationMetrics).recordSuccess();
    }

    private AppointmentEvent createMockAppointmentEvent() {
        AppointmentEvent event = new AppointmentEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("BOOKED");
        event.setAppointmentId(1L);
        event.setPatientId(100L);
        event.setDoctorId(200L);
        event.setPatientName("John Doe");
        event.setDoctorName("Dr. Jane Smith");
        event.setScheduledAt(java.time.LocalDateTime.of(2026, 5, 20, 10, 0));
        return event;
    }
}
