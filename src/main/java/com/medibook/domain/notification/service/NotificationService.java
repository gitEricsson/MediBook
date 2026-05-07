package com.medibook.domain.notification.service;

import com.medibook.domain.notification.entity.Notification;
import com.medibook.domain.notification.repository.NotificationRepository;
import com.medibook.messaging.event.AppointmentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final CassandraOperations cassandraOperations;
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @Async
    public void sendAppointmentBooked(AppointmentEvent event) {
        // Notify patient
        save(event.getPatientId(), "Appointment Booked",
                "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is booked.",
                "APPOINTMENT_BOOKED", event.getAppointmentId());

        // Notify doctor
        save(event.getDoctorId(), "New Appointment",
                "Patient " + event.getPatientName() + " booked an appointment on " + event.getScheduledAt(),
                "APPOINTMENT_BOOKED", event.getAppointmentId());
    }

    @Async
    public void sendAppointmentConfirmed(AppointmentEvent event) {
        save(event.getPatientId(), "Appointment Confirmed",
                "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is confirmed.",
                "APPOINTMENT_CONFIRMED", event.getAppointmentId());
    }

    @Async
    public void sendAppointmentCancelled(AppointmentEvent event) {
        save(event.getPatientId(), "Appointment Cancelled",
                "Your appointment on " + event.getScheduledAt() + " has been cancelled.",
                "APPOINTMENT_CANCELLED", event.getAppointmentId());

        save(event.getDoctorId(), "Appointment Cancelled",
                "Appointment with " + event.getPatientName() + " on " + event.getScheduledAt() + " has been cancelled.",
                "APPOINTMENT_CANCELLED", event.getAppointmentId());
    }

    public List<Notification> getRecent(Long userId) {
        return notificationRepository.findRecentByUserId(userId);
    }

    public List<Notification> getUnread(Long userId) {
        return notificationRepository.findUnreadByUserId(userId);
    }

    public long getUnreadCount(Long userId) {
        return notificationRepository.findUnreadByUserId(userId).size();
    }

    public void markAsRead(Long userId, Instant createdAt, UUID notificationId) {
        // Cassandra update needs the full primary key
        Notification notification = cassandraOperations.selectOne(
                Query.query(Criteria.where("user_id").is(userId),
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
        unread.forEach(n -> {
            n.setRead(true);
            n.setReadAt(Instant.now());
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

    private void removeEmitter(Long userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
        }
    }

    @Async
    private void emit(Long userId, Notification notification) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            for (SseEmitter emitter : userEmitters) {
                try {
                    emitter.send(notification);
                } catch (Exception e) {
                    emitter.complete();
                    userEmitters.remove(emitter);
                }
            }
        }
    }

    @Async
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
        notificationRepository.save(notification);
        emit(userId, notification);
        log.debug("Notification saved and emitted for user [{}]: {}", userId, title);
    }

    /** Delete notifications older than 30 days — runs nightly at 03:00 */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpiredNotifications() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        boolean deleted = cassandraOperations.delete(
                Query.query(Criteria.where("created_at").lt(cutoff)),
                Notification.class);
        log.info("Expired notification purge completed. Any rows deleted: {}", deleted);
    }
}
