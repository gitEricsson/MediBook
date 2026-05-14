package com.medibook.domain.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.common.exception.TemporaryFailureException;
import com.medibook.domain.notification.dto.NotificationResponse;
import com.medibook.domain.notification.entity.Notification;
import com.medibook.domain.notification.repository.NotificationRepository;
import com.medibook.infrastructure.metrics.NotificationMetrics;
import com.medibook.messaging.event.AppointmentEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Notification lifecycle service.
 *
 * Flow:
 *   1. Domain event (e.g. AppointmentEvent) arrives via Kafka consumer.
 *   2. save() inserts the Notification into Cassandra (durable, 30-day TTL).
 *   3. save() publishes the serialised payload to Redis Pub/Sub channel
 *      "notifications:user:{userId}" — this is the horizontal-scalability mechanism.
 *      Every backend replica is subscribed to this pattern.
 *   4. onMessage() fires on all replicas.  The replica that holds the user's
 *      WebSocket connection delivers the notification via STOMP.
 *      The others silently no-op (convertAndSendToUser is a no-op if the user
 *      has no active session on this instance).
 *   5. If the user is offline, the notification remains queryable via REST.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService implements MessageListener {

    private static final Duration NOTIFICATION_TTL = Duration.ofDays(30);
    private static final String   PUBSUB_PREFIX     = "notifications:user:";
    private static final String   WS_DESTINATION    = "/queue/notifications";
    private static final String   UNREAD_COUNT_CACHE = "notificationUnreadCounts";

    private final NotificationRepository        notificationRepository;
    private final CassandraOperations           cassandraOperations;
    private final StringRedisTemplate           stringRedisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper                  objectMapper;
    private final SimpMessagingTemplate         messagingTemplate;
    private final CacheManager                  cacheManager;
    private final RetryTemplate                 notificationRetryTemplate;
    private final NotificationMetrics           notificationMetrics;

    @PostConstruct
    public void registerPubSubListener() {
        listenerContainer.addMessageListener(this, new PatternTopic(PUBSUB_PREFIX + "*"));
        log.info("Registered Redis pub/sub listener on pattern {}*", PUBSUB_PREFIX);
    }

    // ─── Domain notification senders ────────────────────────────────────────

    public void sendAppointmentBooked(AppointmentEvent event) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(event.getPatientId(), "Appointment Booked",
                        "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is booked.",
                        "APPOINTMENT_BOOKED", event.getAppointmentId());
                save(event.getDoctorId(), "New Appointment",
                        "Patient " + event.getPatientName() + " booked an appointment on " + event.getScheduledAt(),
                        "APPOINTMENT_BOOKED", event.getAppointmentId());
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send appointment booked notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending appointment booked notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendAppointmentConfirmed(AppointmentEvent event) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(event.getPatientId(), "Appointment Confirmed",
                        "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is confirmed.",
                        "APPOINTMENT_CONFIRMED", event.getAppointmentId());
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send appointment confirmed notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending appointment confirmed notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendAppointmentCancelled(AppointmentEvent event) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(event.getPatientId(), "Appointment Cancelled",
                        "Your appointment on " + event.getScheduledAt() + " has been cancelled.",
                        "APPOINTMENT_CANCELLED", event.getAppointmentId());
                save(event.getDoctorId(), "Appointment Cancelled",
                        "Appointment with " + event.getPatientName() + " on " + event.getScheduledAt() + " has been cancelled.",
                        "APPOINTMENT_CANCELLED", event.getAppointmentId());
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send appointment cancelled notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending appointment cancelled notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendAppointmentReminder(AppointmentEvent event) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(event.getPatientId(), "Appointment Reminder",
                        "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is tomorrow.",
                        "APPOINTMENT_REMINDER", event.getAppointmentId());
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send appointment reminder notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending appointment reminder notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendPaymentSucceeded(Long patientId, String providerRef, String amount, String currency) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(patientId, "Payment Successful",
                        String.format("Your payment of %s %s (ref: %s) was processed successfully.", amount, currency, providerRef),
                        "PAYMENT_SUCCEEDED", null);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send payment succeeded notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending payment succeeded notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendPaymentFailed(Long patientId, String providerRef) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(patientId, "Payment Failed",
                        "Your payment (ref: " + providerRef + ") could not be processed. Please try again or use a different payment method.",
                        "PAYMENT_FAILED", null);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send payment failed notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending payment failed notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendRefundIssued(Long patientId, String amount, String currency) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(patientId, "Refund Issued",
                        String.format("A refund of %s %s has been processed and will appear in your account within 3–7 business days.", amount, currency),
                        "REFUND_ISSUED", null);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send refund issued notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending refund issued notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendReviewApproved(Long patientId, String doctorName) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(patientId, "Review Published",
                        "Your review for Dr. " + doctorName + " has been approved and is now visible.",
                        "REVIEW_APPROVED", null);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send review approved notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending review approved notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendWaitlistPromoted(Long patientId, Long appointmentId, String doctorName, Object scheduledAt) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(patientId, "Waitlist: Slot Available!",
                        "Good news! A slot with Dr. " + doctorName + " on " + scheduledAt + " opened up and has been reserved for you.",
                        "WAITLIST_PROMOTED", appointmentId);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send waitlist promoted notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending waitlist promoted notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendWaitlistJoined(Long patientId, String doctorName) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(patientId, "Waitlist Joined",
                        "You've successfully joined the waitlist for Dr. " + doctorName,
                        "WAITLIST_JOINED", null);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send waitlist joined notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending waitlist joined notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendTelemedicineSessionReady(Long patientId, Long doctorId, Long appointmentId) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(patientId, "Video Consultation Ready",
                        "Your telemedicine session is ready. Click to join.",
                        "TELEMEDICINE_READY", appointmentId);
                save(doctorId, "Patient Waiting",
                        "A patient is waiting for the telemedicine session.",
                        "TELEMEDICINE_PATIENT_WAITING", appointmentId);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send telemedicine session ready notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending telemedicine session ready notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendTelemedicinePatientWaiting(Long doctorId, Long appointmentId) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(doctorId, "Patient Waiting",
                        "Your patient has entered the waiting room for appointment #" + appointmentId,
                        "TELEMEDICINE_PATIENT_WAITING", appointmentId);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send telemedicine patient waiting notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending telemedicine patient waiting notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendUrgencyAlert(Long doctorId, Long conversationId, String urgencyKeywords) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(doctorId, "Urgent Chat Alert",
                        "A patient message may need urgent review: " + urgencyKeywords,
                        "CHAT_URGENCY_ALERT", conversationId);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send urgency alert notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending urgency alert notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendAccessRequestNotification(Long patientId, String doctorName, Long grantId) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(patientId, "Record Access Request",
                        "Dr. " + doctorName + " has requested access to view your consultation history. You can approve or deny this request.",
                        "ACCESS_REQUEST", grantId);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send access request notification", e);
            } catch (Exception e) {
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendAccessGrantedNotification(Long doctorId, String patientName) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(doctorId, "Record Access Approved",
                        patientName + " has approved your request to view their consultation history.",
                        "ACCESS_GRANTED", null);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send access granted notification", e);
            } catch (Exception e) {
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendChatEscalationRequired(Long doctorId, Long appointmentId) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(doctorId, "Escalation Required",
                        "AI has flagged a conversation for your clinical review.",
                        "CHAT_ESCALATION", appointmentId);
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Notification send failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send chat escalation required notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending chat escalation required notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    // ─── REST query methods ──────────────────────────────────────────────────

    public List<NotificationResponse> getRecent(Long userId) {
        return notificationRepository.findRecentByUserId(userId).stream()
                .map(NotificationResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public List<NotificationResponse> getUnread(Long userId) {
        return notificationRepository.findUnreadByUserId(userId).stream()
                .map(NotificationResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public long getUnreadCount(Long userId) {
        Cache cache = cacheManager.getCache(UNREAD_COUNT_CACHE);
        if (cache != null) {
            Long cached = cache.get(userId, Long.class);
            if (cached != null) {
                return cached;
            }
        }

        long count = notificationRepository.findUnreadByUserId(userId).size();
        if (cache != null) {
            cache.put(userId, count);
        }
        return count;
    }

    public void markAsRead(Long userId, Instant createdAt, UUID notificationId) {
        Notification notification = cassandraOperations.selectOne(
                Query.query(
                        Criteria.where("user_id").is(userId),
                        Criteria.where("created_at").is(createdAt),
                        Criteria.where("notification_id").is(notificationId)),
                Notification.class);

        if (notification != null) {
            boolean wasUnread = !notification.isRead();
            notification.setRead(true);
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
            if (wasUnread) {
                evictUnreadCount(userId);
            }
        }
    }

    public void markAllRead(Long userId) {
        List<Notification> unread = notificationRepository.findUnreadByUserId(userId);
        Instant now = Instant.now();
        unread.forEach(n -> {
            n.setRead(true);
            n.setReadAt(now);
        });
        notificationRepository.saveAll(unread);
        if (!unread.isEmpty()) {
            evictUnreadCount(userId);
        }
    }

    // ─── Redis Pub/Sub inbound handler ──────────────────────────────────────

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body    = new String(message.getBody(), StandardCharsets.UTF_8);
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            Long userId    = Long.parseLong(channel.substring(PUBSUB_PREFIX.length()));

            NotificationResponse notification = objectMapper.readValue(body, NotificationResponse.class);
            deliverViaWebSocket(userId, notification);
        } catch (Exception e) {
            log.error("Failed to process Redis pub/sub notification message", e);
        }
    }

    // ─── Persistence + Redis fan-out ────────────────────────────────────────

    protected void save(Long userId, String title, String message, String type, Long appointmentId) {
        Notification notification = Notification.builder()
                .userId(userId)
                .notificationId(UUID.randomUUID())
                .createdAt(Instant.now())
                .title(title)
                .message(message)
                .type(type)
                .read(false)
                .appointmentId(appointmentId)
                .referenceId(String.valueOf(appointmentId))
                .build();

        // 1. Persist first — REST fallback remains available even if WS/Redis fails
        cassandraOperations.insert(notification,
                InsertOptions.builder().ttl(NOTIFICATION_TTL).build());

        // 2. Publish to Redis Pub/Sub for cross-instance fan-out
        NotificationResponse payload = NotificationResponse.fromEntity(notification);
        try {
            stringRedisTemplate.convertAndSend(
                    PUBSUB_PREFIX + userId,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Redis pub/sub publish failed for user [{}]; notification still persisted", userId, e);
        }

        evictUnreadCount(userId);

        log.debug("Notification saved and published; userId={} type={}", userId, type);
    }

    // ─── WebSocket delivery (best-effort) ───────────────────────────────────

    private void deliverViaWebSocket(Long userId, NotificationResponse payload) {
        try {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(userId),
                    WS_DESTINATION,
                    payload);
            log.debug("WebSocket notification delivered; userId={} type={}", userId, payload.getType());
        } catch (Exception e) {
            // Best-effort: user may be offline; notification is already in Cassandra
            log.debug("WebSocket delivery skipped; userId={} (user likely offline): {}", userId, e.getMessage());
        }
    }

    private void evictUnreadCount(Long userId) {
        Cache cache = cacheManager.getCache(UNREAD_COUNT_CACHE);
        if (cache != null) {
            cache.evict(userId);
        }
    }
}
