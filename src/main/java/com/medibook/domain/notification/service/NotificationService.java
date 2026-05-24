package com.medibook.domain.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.common.exception.TemporaryFailureException;
import com.medibook.common.mail.TransactionalEmailService;
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
    private final TransactionalEmailService     transactionalEmailService;

    @PostConstruct
    public void registerPubSubListener() {
        listenerContainer.addMessageListener(this, new PatternTopic(PUBSUB_PREFIX + "*"));
        log.info("Registered Redis pub/sub listener on pattern {}*", PUBSUB_PREFIX);
    }

    public void sendAppointmentBooked(AppointmentEvent event) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(event.getPatientId(), "Appointment Booked",
                        "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is booked.",
                        "APPOINTMENT_BOOKED", event.getAppointmentId());
                save(event.getDoctorId(), "New Appointment",
                        "Patient " + event.getPatientName() + " booked an appointment on " + event.getScheduledAt(),
                        "APPOINTMENT_BOOKED", event.getAppointmentId());
                // Best-effort transactional emails. Email failures must not retry the
                sendEmailSafely(event.getPatientEmail(),
                        "Your MediBook appointment is booked",
                        bookedEmailBody(event));
                sendEmailSafely(event.getDoctorEmail(),
                        "New patient appointment booked",
                        doctorBookedEmailBody(event));
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
                // Patient
                save(event.getPatientId(), "Appointment Confirmed",
                        "Your appointment with Dr. " + event.getDoctorName() + " on " + event.getScheduledAt() + " is confirmed.",
                        "APPOINTMENT_CONFIRMED", event.getAppointmentId());
                sendEmailSafely(event.getPatientEmail(),
                        "Your MediBook appointment is confirmed",
                        confirmedEmailBody(event));
                // Doctor
                if (event.getDoctorId() != null) {
                    save(event.getDoctorId(), "Appointment Confirmed",
                            "Appointment with " + event.getPatientName() + " on " + event.getScheduledAt() + " is now confirmed.",
                            "APPOINTMENT_CONFIRMED", event.getAppointmentId());
                    sendEmailSafely(event.getDoctorEmail(),
                            "Patient appointment confirmed",
                            doctorConfirmedEmailBody(event));
                }
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
                sendEmailSafely(event.getPatientEmail(),
                        "Your MediBook appointment was cancelled",
                        cancelledEmailBody(event, /*toPatient*/ true));
                sendEmailSafely(event.getDoctorEmail(),
                        "Patient appointment cancelled",
                        cancelledEmailBody(event, /*toPatient*/ false));
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
                int hours = event.getHoursBeforeAppointment() != null ? event.getHoursBeforeAppointment() : 24;
                String timeLabel = switch (hours) {
                    case 48 -> "in 2 days";
                    case 2  -> "in 2 hours";
                    default -> "tomorrow";
                };
                String subject = "Reminder: Your appointment is " + timeLabel;
                String body = "Your appointment with Dr. " + event.getDoctorName()
                        + " is scheduled for " + event.getScheduledAt() + ". "
                        + "Please ensure you are on time.";

                // ── Patient: in-app + email ───────────────────────────────
                save(event.getPatientId(), subject, body, "APPOINTMENT_REMINDER", event.getAppointmentId());
                if (event.getPatientEmail() != null && !event.getPatientEmail().isBlank()) {
                    String patientDetails = detailsTable(
                            "Doctor",     "Dr. " + safe(event.getDoctorName()),
                            "When",       formatDateTime(event.getScheduledAt()),
                            "Reference",  "#" + event.getAppointmentId()
                    );
                    String patientHtml = wrap(
                            "Your appointment is " + timeLabel,
                            "Reminder · " + hours + "h",
                            "Hi " + safe(event.getPatientName()) + ",",
                            "This is a friendly reminder that your appointment is coming up " + timeLabel + ". Please make sure you're on time.",
                            patientDetails,
                            "If you need to reschedule or cancel, do so from My Visits in the app.",
                            ctaButton("https://app.medibook.health/patient/appts", "View appointment")
                    );
                    transactionalEmailService.sendHtml(event.getPatientEmail(), subject, patientHtml);
                }

                // ── Doctor: in-app + email ────────────────────────────────
                if (event.getDoctorId() != null) {
                    String doctorBody = "Upcoming appointment with " + event.getPatientName()
                            + " is " + timeLabel + " (" + event.getScheduledAt() + ").";
                    save(event.getDoctorId(), "Upcoming appointment " + timeLabel,
                            doctorBody, "APPOINTMENT_REMINDER", event.getAppointmentId());
                    if (event.getDoctorEmail() != null && !event.getDoctorEmail().isBlank()) {
                        String doctorDetails = detailsTable(
                                "Patient",    safe(event.getPatientName()),
                                "When",       formatDateTime(event.getScheduledAt()),
                                "Reference",  "#" + event.getAppointmentId()
                        );
                        String doctorHtml = wrap(
                                "Upcoming patient appointment " + timeLabel,
                                "Reminder · " + hours + "h",
                                "Hi Dr. " + safe(event.getDoctorName()) + ",",
                                "You have an upcoming appointment on your schedule.",
                                doctorDetails,
                                null,
                                ctaButton("https://app.medibook.health/doctor/schedule", "Open schedule")
                        );
                        transactionalEmailService.sendHtml(event.getDoctorEmail(),
                                "Upcoming patient appointment " + timeLabel, doctorHtml);
                    }
                }

                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Reminder notification failed (attempt {}), will retry: {}", context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send appointment reminder notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending appointment reminder notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendEmergencyConsultationRequested(AppointmentEvent event) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(event.getDoctorId(), "Emergency Consultation",
                        "Emergency request from " + safe(event.getPatientName())
                                + " is waiting now. Appointment #" + event.getAppointmentId() + ".",
                        "EMERGENCY_CONSULTATION_REQUESTED", event.getAppointmentId());
                save(event.getPatientId(), "Emergency Consultation Started",
                        "You have been connected with Dr. " + safe(event.getDoctorName()) + ".",
                        "EMERGENCY_CONSULTATION_STARTED", event.getAppointmentId());
                sendEmailSafely(event.getDoctorEmail(),
                        "Emergency consultation assigned",
                        emergencyDoctorEmailBody(event));
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                log.warn("Emergency notification failed (attempt {}), will retry: {}",
                        context.getRetryCount() + 1, e.getMessage());
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send emergency consultation notification", e);
            } catch (Exception e) {
                log.error("Permanent failure sending emergency consultation notification", e);
                notificationMetrics.recordPermanentFailure();
                throw e;
            }
        });
    }

    public void sendOutstandingBalanceCreated(AppointmentEvent event) {
        notificationRetryTemplate.execute(context -> {
            try {
                save(event.getPatientId(), "Emergency Bill Due",
                        "Your emergency consultation bill is ready. Please settle it before requesting another non-critical emergency consultation.",
                        "OUTSTANDING_BALANCE_CREATED", event.getAppointmentId());
                notificationMetrics.recordSuccess();
                return null;
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
                    notificationMetrics.recordPermanentFailure();
                    throw e;
                }
                notificationMetrics.recordRetry();
                throw new TemporaryFailureException("Failed to send outstanding balance notification", e);
            } catch (Exception e) {
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

    public List<NotificationResponse> getRecent(Long userId) {
        try {
            return notificationRepository.findRecentByUserId(userId).stream()
                    .map(NotificationResponse::fromEntity)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to fetch recent notifications for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    public List<NotificationResponse> getUnread(Long userId) {
        try {
            return notificationRepository.findUnreadByUserId(userId).stream()
                    .map(NotificationResponse::fromEntity)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to fetch unread notifications for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    public long getUnreadCount(Long userId) {
        Cache cache = cacheManager.getCache(UNREAD_COUNT_CACHE);
        // Cache reads are best-effort. A Redis outage must not propagate as 5xx —
        // the count is recoverable from Cassandra and the cache is just an accelerator.
        if (cache != null) {
            try {
                Long cached = cache.get(userId, Long.class);
                if (cached != null) {
                    return cached;
                }
            } catch (Exception e) {
                log.warn("Unread-count cache GET failed for user {}; falling through to Cassandra: {}",
                        userId, e.getMessage());
            }
        }

        long count;
        try {
            count = notificationRepository.findUnreadByUserId(userId).size();
        } catch (Exception e) {
            log.warn("Failed to query unread notification count for user {} from Cassandra: {}",
                    userId, e.getMessage());
            return 0L;
        }
        if (cache != null) {
            try {
                cache.put(userId, count);
            } catch (Exception e) {
                log.warn("Unread-count cache PUT failed for user {}: {}", userId, e.getMessage());
            }
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
            try {
                cache.evict(userId);
            } catch (Exception e) {
                // Best-effort — the cached value will simply stay stale until TTL.
                log.warn("Unread-count cache EVICT failed for user {}: {}", userId, e.getMessage());
            }
        }
    }

    /**
     * Send a transactional email without letting a delivery failure bubble up.
     * In-app + STOMP notifications have already succeeded by the time we reach here,
     * and the user can always read the notification in-app. Email is a courtesy layer.
     */
    private void sendEmailSafely(String toEmail, String subject, String htmlBody) {
        if (toEmail == null || toEmail.isBlank()) return;
        try {
            transactionalEmailService.sendHtml(toEmail, subject, htmlBody);
        } catch (Exception ex) {
            log.warn("Appointment email to {} failed: {}", toEmail, ex.getMessage());
        }
    }

    private String bookedEmailBody(AppointmentEvent event) {
        String details = detailsTable(
                "Doctor",      "Dr. " + safe(event.getDoctorName()),
                "Department",  event.getDepartmentName() != null ? safe(event.getDepartmentName()) : "—",
                "When",        formatDateTime(event.getScheduledAt()),
                "Reference",   "#" + event.getAppointmentId()
        );
        return wrap(
                "Your appointment is booked",
                "Booked",
                "Hi " + safe(event.getPatientName()) + ",",
                "Your booking has been received. The slot is held for you and will be fully confirmed once payment lands.",
                details,
                "Complete payment from My Visits in the app to lock in the slot.",
                ctaButton("https://app.medibook.health/patient/appts", "Pay & confirm")
        );
    }

    private String doctorBookedEmailBody(AppointmentEvent event) {
        String details = detailsTable(
                "Patient",    safe(event.getPatientName()),
                "When",       formatDateTime(event.getScheduledAt()),
                "Reference",  "#" + event.getAppointmentId()
        );
        return wrap(
                "New patient booking",
                "New booking",
                "Hi Dr. " + safe(event.getDoctorName()) + ",",
                "A new appointment has just been booked on your schedule.",
                details,
                "Open your schedule to review it.",
                ctaButton("https://app.medibook.health/doctor/schedule", "View schedule")
        );
    }

    private String confirmedEmailBody(AppointmentEvent event) {
        String details = detailsTable(
                "Doctor",      "Dr. " + safe(event.getDoctorName()),
                "When",        formatDateTime(event.getScheduledAt()),
                "Reference",   "#" + event.getAppointmentId()
        );
        return wrap(
                "Your appointment is confirmed",
                "Confirmed",
                "Hi " + safe(event.getPatientName()) + ",",
                "Payment received — your appointment is now locked in. We'll send reminders 48, 24, and 2 hours before the visit.",
                details,
                null,
                ctaButton("https://app.medibook.health/patient/appts", "View appointment")
        );
    }

    private String doctorConfirmedEmailBody(AppointmentEvent event) {
        String details = detailsTable(
                "Patient",    safe(event.getPatientName()),
                "When",       formatDateTime(event.getScheduledAt()),
                "Reference",  "#" + event.getAppointmentId()
        );
        return wrap(
                "Patient appointment confirmed",
                "Confirmed",
                "Hi Dr. " + safe(event.getDoctorName()) + ",",
                "The patient has completed payment and the appointment is locked into your schedule.",
                details,
                null,
                ctaButton("https://app.medibook.health/doctor/schedule", "View schedule")
        );
    }

    private String emergencyDoctorEmailBody(AppointmentEvent event) {
        String details = detailsTable(
                "Patient", safe(event.getPatientName()),
                "Started", formatDateTime(event.getScheduledAt()),
                "Reference", "#" + event.getAppointmentId()
        );
        return wrap(
                "Emergency consultation assigned",
                "Emergency",
                "Hi Dr. " + safe(event.getDoctorName()) + ",",
                "A patient is waiting for an emergency consultation now.",
                details,
                "Open MediBook to join the consultation.",
                ctaButton("https://app.medibook.health/doctor/schedule", "Join consultation")
        );
    }

    private String cancelledEmailBody(AppointmentEvent event, boolean toPatient) {
        String greet = toPatient
                ? "Hi " + safe(event.getPatientName()) + ","
                : "Hi Dr. " + safe(event.getDoctorName()) + ",";
        String intro = toPatient
                ? "Your appointment with Dr. " + safe(event.getDoctorName()) + " has been cancelled. If a payment was made, the refund will be processed within 5–7 business days."
                : "The appointment with patient " + safe(event.getPatientName()) + " has been cancelled. The slot is now free on your schedule.";
        String details = toPatient
                ? detailsTable(
                        "Doctor",     "Dr. " + safe(event.getDoctorName()),
                        "When",       formatDateTime(event.getScheduledAt()),
                        "Reference",  "#" + event.getAppointmentId())
                : detailsTable(
                        "Patient",    safe(event.getPatientName()),
                        "When",       formatDateTime(event.getScheduledAt()),
                        "Reference",  "#" + event.getAppointmentId());
        return wrap(
                "Appointment cancelled",
                "Cancelled",
                greet,
                intro,
                details,
                null,
                null
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Email shell + helpers
    //
    //  Mail-client safe: table-based layout, inline styles, web-safe fonts.
    //  Avoid background-image, transform, flexbox, grid, CSS vars.
    //  Linear gradients are tolerated by modern clients; older clients fall
    //  back to the solid background-color set immediately before them.
    // ────────────────────────────────────────────────────────────────────────

    private static final String BRAND       = "#0E8A5F";
    private static final String BRAND_DARK  = "#086043";
    private static final String BRAND_DARKER= "#054732";
    private static final String BRAND_50    = "#ECFAF3";
    private static final String BRAND_100   = "#D1F1E0";
    private static final String INK         = "#0F172A";
    private static final String TEXT        = "#1F2937";
    private static final String TEXT_2      = "#4B5563";
    private static final String MUTED       = "#6B7280";
    private static final String MUTED_LIGHT = "#9CA3AF";
    private static final String LINE        = "#E5E7EB";
    private static final String BG          = "#F4F6F8";

    /** Polished branded shell. */
    private String wrap(String title, String inner) {
        // Backwards-compatible single-block variant.
        return wrap(title, null, null, null, inner, null, null);
    }

    /** Polished branded shell with explicit content sections + optional status pill. */
    private String wrap(String title, String greeting, String intro, String body, String aside, String cta) {
        return wrap(title, null, greeting, intro, body, aside, cta);
    }

    /**
     * Advanced, branded email shell.
     *
     * Layout (top → bottom):
     *   • Pre-header (hidden inbox preview text)
     *   • Hero card: gradient brand bar with MediBook logo wordmark, then
     *     a status pill, large title, optional greeting + intro on white.
     *   • Body card: caller-supplied HTML (typically a details card).
     *   • Optional aside (muted callout).
     *   • Optional CTA button (bullet-proof, with VML for Outlook).
     *   • "Need help?" support strip.
     *   • Footer: small logo, links, address, copyright.
     *
     * @param statusPill optional small uppercase label rendered as a pill under
     *                   the title (e.g. "BOOKED", "CONFIRMED", "REMINDER").
     */
    private String wrap(String title, String statusPill, String greeting, String intro, String body, String aside, String cta) {
        StringBuilder s = new StringBuilder(4096);
        s.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">")
         .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">")
         .append("<head><meta charset=\"utf-8\"/>")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
         .append("<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"/>")
         .append("<title>").append(safe(title)).append("</title>")
         .append("<!--[if mso]><xml><o:OfficeDocumentSettings><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]-->")
         .append("</head>")
         .append("<body style=\"margin:0;padding:0;background:").append(BG)
         .append(";font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:").append(TEXT).append(";-webkit-font-smoothing:antialiased;\">")

         // Preheader (hidden in body, shown by inbox preview)
         .append("<div style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;font-size:1px;line-height:1px;color:").append(BG).append(";\">")
         .append(safe(intro != null ? intro : title))
         .append("&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;")
         .append("</div>")

         // Outer wrapper
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:").append(BG).append(";padding:40px 16px;\">")
         .append("<tr><td align=\"center\">")

         // Card
         .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"max-width:600px;width:100%;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid ").append(LINE).append(";\">")

         // ── HERO BAND (gradient) ─────────────────────────────────────────
         .append("<tr><td style=\"background-color:").append(BRAND_DARK).append(";")
         .append("background-image:linear-gradient(135deg,").append(BRAND).append(" 0%,").append(BRAND_DARK).append(" 60%,").append(BRAND_DARKER).append(" 100%);")
         .append("padding:28px 32px 26px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         // Logo + wordmark
         .append("<td style=\"vertical-align:middle;\">")
         .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td style=\"width:36px;height:36px;background:#ffffff;border-radius:10px;text-align:center;vertical-align:middle;line-height:36px;\">")
         .append("<span style=\"font-size:22px;font-weight:800;color:").append(BRAND).append(";font-family:Helvetica,Arial,sans-serif;\">+</span>")
         .append("</td>")
         .append("<td style=\"padding-left:12px;vertical-align:middle;\">")
         .append("<div style=\"color:#ffffff;font-size:17px;font-weight:700;letter-spacing:-0.01em;line-height:1.1;\">MediBook</div>")
         .append("<div style=\"color:rgba(255,255,255,0.7);font-size:11px;font-weight:500;letter-spacing:0.08em;text-transform:uppercase;line-height:1.1;margin-top:3px;\">Care, on schedule</div>")
         .append("</td>")
         .append("</tr></table>")
         .append("</td>")
         // Right-aligned tag
         .append("<td align=\"right\" style=\"vertical-align:middle;\">")
         .append("<span style=\"display:inline-block;padding:5px 11px;background:rgba(255,255,255,0.16);border:1px solid rgba(255,255,255,0.28);border-radius:999px;font-size:11px;font-weight:600;letter-spacing:0.06em;text-transform:uppercase;color:#ffffff;\">Healthcare</span>")
         .append("</td>")
         .append("</tr></table>")
         .append("</td></tr>")

         // ── TITLE BLOCK ──────────────────────────────────────────────────
         .append("<tr><td style=\"padding:36px 32px 0;\">");

        if (statusPill != null) {
            s.append("<div style=\"margin:0 0 14px;\">")
             .append("<span style=\"display:inline-block;padding:5px 12px;background:").append(BRAND_50).append(";color:").append(BRAND_DARK)
             .append(";font-size:11px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;border-radius:999px;border:1px solid ").append(BRAND_100).append(";\">")
             .append(safe(statusPill)).append("</span>")
             .append("</div>");
        }

        s.append("<h1 style=\"margin:0;font-size:26px;line-height:1.25;font-weight:800;color:").append(INK).append(";letter-spacing:-0.02em;\">")
         .append(safe(title)).append("</h1>");

        if (greeting != null) {
            s.append("<p style=\"margin:18px 0 0;font-size:15px;color:").append(TEXT).append(";font-weight:500;\">")
             .append(safe(greeting)).append("</p>");
        }
        s.append("</td></tr>");

        // ── INTRO ───────────────────────────────────────────────────────
        if (intro != null) {
            s.append("<tr><td style=\"padding:12px 32px 0;\"><p style=\"margin:0;font-size:15px;line-height:1.65;color:").append(TEXT_2).append(";\">")
             .append(safe(intro)).append("</p></td></tr>");
        }

        // ── BODY (caller HTML — already escaped in generators) ─────────
        if (body != null && !body.isBlank()) {
            s.append("<tr><td style=\"padding:22px 32px 0;font-size:15px;line-height:1.65;color:").append(TEXT_2).append(";\">")
             .append(body).append("</td></tr>");
        }

        // ── ASIDE ───────────────────────────────────────────────────────
        if (aside != null) {
            s.append("<tr><td style=\"padding:20px 32px 0;\">")
             .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:#FAFBFC;border-left:3px solid ").append(BRAND).append(";border-radius:6px;\"><tr>")
             .append("<td style=\"padding:12px 16px;font-size:13px;line-height:1.55;color:").append(TEXT_2).append(";\">")
             .append(safe(aside))
             .append("</td></tr></table>")
             .append("</td></tr>");
        }

        // ── CTA (bullet-proof, VML for Outlook) ─────────────────────────
        if (cta != null) {
            s.append("<tr><td style=\"padding:28px 32px 8px;\">").append(cta).append("</td></tr>");
        }

        // ── HELP STRIP ──────────────────────────────────────────────────
        s.append("<tr><td style=\"padding:32px 32px 0;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:").append(BRAND_50).append(";border-radius:12px;\"><tr>")
         .append("<td style=\"padding:14px 18px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td style=\"vertical-align:middle;\">")
         .append("<div style=\"font-size:13px;font-weight:700;color:").append(BRAND_DARKER).append(";\">Need a hand?</div>")
         .append("<div style=\"font-size:12px;color:").append(TEXT_2).append(";margin-top:2px;\">Our support team replies within a few hours.</div>")
         .append("</td>")
         .append("<td align=\"right\" style=\"vertical-align:middle;\">")
         .append("<a href=\"mailto:support@medibook.health\" style=\"font-size:13px;font-weight:600;color:").append(BRAND_DARK).append(";text-decoration:none;\">Contact support &rarr;</a>")
         .append("</td>")
         .append("</tr></table>")
         .append("</td></tr></table>")
         .append("</td></tr>");

        // ── FOOTER ──────────────────────────────────────────────────────
        s.append("<tr><td style=\"padding:28px 32px 32px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">")
         // Mini logo row
         .append("<tr><td style=\"padding-bottom:14px;border-bottom:1px solid ").append(LINE).append(";\">")
         .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td style=\"width:22px;height:22px;background:").append(BRAND).append(";border-radius:6px;text-align:center;vertical-align:middle;line-height:22px;\">")
         .append("<span style=\"font-size:14px;font-weight:800;color:#ffffff;font-family:Helvetica,Arial,sans-serif;\">+</span>")
         .append("</td>")
         .append("<td style=\"padding-left:8px;vertical-align:middle;font-size:13px;font-weight:700;color:").append(INK).append(";\">MediBook</td>")
         .append("</tr></table>")
         .append("</td></tr>")

         // Disclaimer
         .append("<tr><td style=\"padding-top:14px;\">")
         .append("<p style=\"margin:0;font-size:12px;line-height:1.55;color:").append(MUTED).append(";\">")
         .append("You're receiving this because you have a MediBook account. This is a transactional email — please do not reply directly.")
         .append("</p>")
         // Footer links
         .append("<p style=\"margin:10px 0 0;font-size:12px;color:").append(MUTED).append(";\">")
         .append("<a href=\"https://app.medibook.health/privacy\" style=\"color:").append(MUTED).append(";text-decoration:none;border-bottom:1px solid ").append(LINE).append(";\">Privacy</a>")
         .append(" &nbsp;&middot;&nbsp; ")
         .append("<a href=\"https://app.medibook.health/terms\" style=\"color:").append(MUTED).append(";text-decoration:none;border-bottom:1px solid ").append(LINE).append(";\">Terms</a>")
         .append(" &nbsp;&middot;&nbsp; ")
         .append("<a href=\"https://app.medibook.health/\" style=\"color:").append(MUTED).append(";text-decoration:none;border-bottom:1px solid ").append(LINE).append(";\">Help center</a>")
         .append(" &nbsp;&middot;&nbsp; ")
         .append("<a href=\"mailto:support@medibook.health\" style=\"color:").append(MUTED).append(";text-decoration:none;border-bottom:1px solid ").append(LINE).append(";\">Support</a>")
         .append("</p>")
         .append("<p style=\"margin:14px 0 0;font-size:11px;color:").append(MUTED_LIGHT).append(";line-height:1.5;\">")
         .append("MediBook Health Ltd. &middot; 12 Marina, Lagos Island, Lagos &middot; Nigeria")
         .append("<br/>&copy; 2026 MediBook Health. All rights reserved.")
         .append("</p>")
         .append("</td></tr>")
         .append("</table>")
         .append("</td></tr>")

         .append("</table>")
         .append("</td></tr></table>")
         .append("</body></html>");

        return s.toString();
    }

    /**
     * Render (label, value) pairs as a polished two-column details card.
     * Each row is icon-prefixed via a coloured leading marker for visual rhythm.
     */
    private String detailsTable(String... pairs) {
        StringBuilder s = new StringBuilder(1024);
        s.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
         .append("style=\"margin:18px 0 4px;background:#ffffff;border:1px solid ").append(LINE).append(";border-radius:12px;overflow:hidden;\">");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            boolean isLast = (i + 2 >= pairs.length);
            String border = isLast ? "" : "border-bottom:1px solid " + LINE + ";";
            s.append("<tr>")
             // Marker column
             .append("<td style=\"width:6px;padding:0;background:").append(BRAND_50).append(";").append(border).append("\">&nbsp;</td>")
             // Label
             .append("<td style=\"padding:12px 8px 12px 16px;font-size:12px;font-weight:600;color:").append(MUTED)
             .append(";text-transform:uppercase;letter-spacing:0.06em;width:36%;").append(border).append("\">")
             .append(safe(pairs[i])).append("</td>")
             // Value
             .append("<td style=\"padding:12px 18px 12px 8px;font-size:15px;font-weight:600;color:").append(INK).append(";").append(border).append("\">")
             .append(safe(pairs[i + 1])).append("</td>")
             .append("</tr>");
        }
        s.append("</table>");
        return s.toString();
    }

    /**
     * Bullet-proof primary CTA. VML used for Outlook 2007-2019 so the rounded
     * background renders; everywhere else the styled <a> takes over.
     */
    private String ctaButton(String url, String label) {
        StringBuilder s = new StringBuilder(640);
        s.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td align=\"center\">")
         // Outlook VML
         .append("<!--[if mso]>")
         .append("<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\" ")
         .append("href=\"").append(url).append("\" style=\"height:48px;v-text-anchor:middle;width:240px;\" arcsize=\"17%\" stroke=\"f\" fillcolor=\"").append(BRAND).append("\">")
         .append("<w:anchorlock/>")
         .append("<center style=\"color:#ffffff;font-family:Helvetica,Arial,sans-serif;font-size:15px;font-weight:700;\">")
         .append(safe(label))
         .append("</center>")
         .append("</v:roundrect>")
         .append("<![endif]-->")
         // Modern clients
         .append("<!--[if !mso]><!-- -->")
         .append("<a href=\"").append(url).append("\" ")
         .append("style=\"display:inline-block;padding:14px 28px;background:").append(BRAND).append(";")
         .append("background-image:linear-gradient(135deg,").append(BRAND).append(" 0%,").append(BRAND_DARK).append(" 100%);")
         .append("color:#ffffff;font-size:15px;font-weight:700;text-decoration:none;border-radius:10px;letter-spacing:0.01em;\">")
         .append(safe(label))
         .append(" &rarr;")
         .append("</a>")
         .append("<!--<![endif]-->")
         .append("</td></tr></table>");
        return s.toString();
    }

    /** Human-readable date/time formatting for emails. */
    private String formatDateTime(java.time.LocalDateTime dt) {
        if (dt == null) return "—";
        try {
            return dt.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a"));
        } catch (Exception e) {
            return dt.toString();
        }
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
