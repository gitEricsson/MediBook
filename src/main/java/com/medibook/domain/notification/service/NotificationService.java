package com.medibook.domain.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.notification.dto.NotificationResponse;
import com.medibook.domain.notification.entity.Notification;
import com.medibook.domain.notification.repository.NotificationRepository;
import com.medibook.messaging.event.AppointmentEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService implements MessageListener {

    private static final Duration   NOTIFICATION_TTL    = Duration.ofDays(30);
    private static final String     PUBSUB_PREFIX       = "notifications:user:";

    private final NotificationRepository          notificationRepository;
    private final CassandraOperations             cassandraOperations;
    private final StringRedisTemplate             stringRedisTemplate;
    private final RedisMessageListenerContainer   listenerContainer;
    private final ObjectMapper                    objectMapper;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @PostConstruct
    public void registerPubSubListener() {
        listenerContainer.addMessageListener(this, new PatternTopic(PUBSUB_PREFIX + "*"));
        log.info("Registered Redis pub/sub listener on pattern {}*", PUBSUB_PREFIX);
    }


    public void sendAppointmentBooked(AppointmentEvent event) {
        save(event.getPatientId(), "Appointment Booked",
                "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is booked.",
                "APPOINTMENT_BOOKED", event.getAppointmentId());
        save(event.getDoctorId(), "New Appointment",
                "Patient " + event.getPatientName() + " booked an appointment on " + event.getScheduledAt(),
                "APPOINTMENT_BOOKED", event.getAppointmentId());
    }

    public void sendAppointmentConfirmed(AppointmentEvent event) {
        save(event.getPatientId(), "Appointment Confirmed",
                "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is confirmed.",
                "APPOINTMENT_CONFIRMED", event.getAppointmentId());
    }

    public void sendAppointmentCancelled(AppointmentEvent event) {
        save(event.getPatientId(), "Appointment Cancelled",
                "Your appointment on " + event.getScheduledAt() + " has been cancelled.",
                "APPOINTMENT_CANCELLED", event.getAppointmentId());
        save(event.getDoctorId(), "Appointment Cancelled",
                "Appointment with " + event.getPatientName() + " on " + event.getScheduledAt() + " has been cancelled.",
                "APPOINTMENT_CANCELLED", event.getAppointmentId());
    }

    public void sendAppointmentReminder(AppointmentEvent event) {
        save(event.getPatientId(), "Appointment Reminder",
                "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is tomorrow.",
                "APPOINTMENT_REMINDER", event.getAppointmentId());
    }

    public void sendPaymentSucceeded(Long patientId, String providerRef, String amount, String currency) {
        save(patientId, "Payment Successful",
                String.format("Your payment of %s %s (ref: %s) was processed successfully.", amount, currency, providerRef),
                "PAYMENT_SUCCEEDED", null);
    }

    public void sendPaymentFailed(Long patientId, String providerRef) {
        save(patientId, "Payment Failed",
                "Your payment (ref: " + providerRef + ") could not be processed. Please try again or use a different payment method.",
                "PAYMENT_FAILED", null);
    }

    public void sendRefundIssued(Long patientId, String amount, String currency) {
        save(patientId, "Refund Issued",
                String.format("A refund of %s %s has been processed and will appear in your account within 3–7 business days.", amount, currency),
                "REFUND_ISSUED", null);
    }

    public void sendReviewApproved(Long patientId, String doctorName) {
        save(patientId, "Review Published",
                "Your review for Dr. " + doctorName + " has been approved and is now visible.",
                "REVIEW_APPROVED", null);
    }

    public void sendWaitlistPromoted(Long patientId, Long appointmentId, String doctorName, Object scheduledAt) {
        save(patientId, "Waitlist: Slot Available!",
                "Good news! A slot with Dr. " + doctorName + " on " + scheduledAt + " opened up and has been reserved for you.",
                "WAITLIST_PROMOTED", appointmentId);
    }

    public void sendTelemedicineSessionReady(Long patientId, Long doctorId, Long appointmentId) {
        save(patientId, "Video Consultation Ready",
                "Your telemedicine session is ready. Click to join.",
                "TELEMEDICINE_READY", appointmentId);
        save(doctorId, "Patient Waiting",
                "A patient is waiting for the telemedicine session.",
                "TELEMEDICINE_PATIENT_WAITING", appointmentId);
    }


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
        return notificationRepository.countUnreadByUserId(userId);
    }

    public void markAsRead(Long userId, Instant createdAt, UUID notificationId) {
        Notification notification = cassandraOperations.selectOne(
                Query.query(
                        Criteria.where("user_id").is(userId),
                        Criteria.where("created_at").is(createdAt),
                        Criteria.where("notification_id").is(notificationId)),
                Notification.class);

        if (notification != null) {
            notification.setRead(true);
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
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
    }


    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        return emitter;
    }


    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body    = new String(message.getBody(), StandardCharsets.UTF_8);
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            Long userId    = Long.parseLong(channel.substring(PUBSUB_PREFIX.length()));

            NotificationResponse notification = objectMapper.readValue(body, NotificationResponse.class);
            emit(userId, notification);
        } catch (Exception e) {
            log.error("Failed to process Redis pub/sub notification message", e);
        }
    }


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

        cassandraOperations.insert(notification,
                InsertOptions.builder().ttl(NOTIFICATION_TTL).build());

        NotificationResponse payload = NotificationResponse.fromEntity(notification);
        try {
            stringRedisTemplate.convertAndSend(
                    PUBSUB_PREFIX + userId,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Failed to publish notification to Redis pub/sub for user [{}]", userId, e);
        }

        log.debug("Notification saved and published for user [{}]: {}", userId, title);
    }

    private void emit(Long userId, NotificationResponse payload) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) return;

        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(payload);
            } catch (Exception e) {
                emitter.complete();
                userEmitters.remove(emitter);
            }
        }
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) userEmitters.remove(emitter);
    }
}
