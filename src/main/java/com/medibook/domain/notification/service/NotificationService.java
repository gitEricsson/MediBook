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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final CassandraOperations cassandraOperations;

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

    public List<Notification> getRecent(Long userId) {
        return notificationRepository.findRecentByUserId(userId);
    }

    public List<Notification> getUnread(Long userId) {
        return notificationRepository.findUnreadByUserId(userId);
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

    private void save(Long userId, String title, String message, String type, Long appointmentId) {
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
        log.debug("Notification saved for user [{}]: {}", userId, title);
    }
}
