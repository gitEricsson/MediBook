package com.medibook.domain.telemedicine.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.telemedicine.dto.ChatMessageRequest;
import com.medibook.domain.telemedicine.dto.ChatMessageResponse;
import com.medibook.domain.telemedicine.dto.TelemedicineSessionResponse;
import com.medibook.domain.telemedicine.entity.ChatMessage;
import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import com.medibook.domain.telemedicine.entity.CassandraChatMessage;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.TelemedicineEvent;
import com.medibook.messaging.producer.OutboxEventProducer;
import java.time.Instant;
import java.util.UUID;
import com.medibook.domain.telemedicine.provider.VideoRoomPort;
import com.medibook.domain.telemedicine.repository.CassandraChatMessageRepository;
import com.medibook.domain.telemedicine.repository.TelemedicineChatMessageRepository;
import com.medibook.domain.telemedicine.repository.TelemedicineSessionRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemedicineSessionService {

    private final TelemedicineSessionRepository sessionRepository;
    private final TelemedicineChatMessageRepository chatMessageRepository;
    private final CassandraChatMessageRepository cassandraChatRepo;
    private final AppointmentRepository         appointmentRepository;
    private final UserRepository                userRepository;
    private final VideoRoomPort                 videoRoomPort;
    private final OutboxEventProducer           eventProducer;

    @Transactional
    public TelemedicineSessionResponse createSession(Long appointmentId, boolean patientConsent, UserPrincipal principal) {
        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", appointmentId));

        // Guard: PHYSICAL consultations have no video/audio channel.
        // We check medium rather than legacy type so FIRST_VISIT/FOLLOW_UP with VIDEO or AUDIO
        // medium are accepted; only explicitly PHYSICAL slots are rejected.
        if (appointment.getConsultationMedium() == null
                || appointment.getConsultationMedium() == ConsultationMedium.PHYSICAL) {
            throw new MediBookException(
                    "Telemedicine sessions can only be created for VIDEO or AUDIO consultations.",
                    HttpStatus.BAD_REQUEST, "PHYSICAL_CONSULTATION_NO_TELEMEDICINE");
        }

        if (!appointment.getPatient().getId().equals(principal.getId())
                && !appointment.getDoctor().getUser().getId().equals(principal.getId())
                && !principal.hasRole("ROLE_ADMIN")) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (sessionRepository.existsByAppointmentId(appointmentId)) {
            throw new MediBookException("Session already exists for this appointment",
                    HttpStatus.CONFLICT, "SESSION_EXISTS");
        }

        VideoRoomPort.CreateRoomResult room = videoRoomPort.createRoom(
                new VideoRoomPort.CreateRoomRequest(
                        appointmentId,
                        appointment.getPatient().getId(),
                        appointment.getDoctor().getId(),
                        appointment.getDurationMins() + 15
                ));

        VideoRoomPort.JoinTokenResult patientJoin = videoRoomPort.generatePatientToken(
                room.roomId(), appointment.getPatient().getId(),
                appointment.getPatient().getFullName());

        VideoRoomPort.JoinTokenResult doctorJoin = videoRoomPort.generateDoctorToken(
                room.roomId(), appointment.getDoctor().getId(),
                appointment.getDoctor().getUser().getFullName());

        TelemedicineSession session = TelemedicineSession.builder()
                .appointment(appointment)
                .status(TelemedicineSessionStatus.SCHEDULED)
                .roomId(room.roomId())
                .joinUrlPatient(patientJoin.joinUrl())
                .joinUrlDoctor(doctorJoin.joinUrl())
                .patientConsent(patientConsent)
                .build();

        TelemedicineSession saved = sessionRepository.save(session);
        log.info("Telemedicine session [{}] created for appointment [{}]", saved.getId(), appointmentId);

        eventProducer.publish("TELEMEDICINE", String.valueOf(saved.getId()), "READY",
                KafkaTopics.TELEMEDICINE_EVENTS,
                TelemedicineEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .eventType("READY")
                        .sessionId(saved.getId())
                        .appointmentId(appointmentId)
                        .patientId(appointment.getPatient().getId())
                        .doctorId(appointment.getDoctor().getUser().getId())
                        .occurredAt(LocalDateTime.now())
                        .build());

        boolean isDoctor = appointment.getDoctor().getUser().getId().equals(principal.getId());
        return TelemedicineSessionResponse.fromEntity(saved, isDoctor);
    }

    @Transactional
    public TelemedicineSessionResponse transitionStatus(Long sessionId, TelemedicineSessionStatus newStatus, UserPrincipal principal) {
        TelemedicineSession session = getSessionWithAuthCheck(sessionId, principal);

        validateTransition(session.getStatus(), newStatus);

        session.setStatus(newStatus);
        if (newStatus == TelemedicineSessionStatus.ACTIVE && session.getStartedAt() == null) {
            session.setStartedAt(LocalDateTime.now());
        }
        if (newStatus == TelemedicineSessionStatus.COMPLETED || newStatus == TelemedicineSessionStatus.FAILED) {
            session.setEndedAt(LocalDateTime.now());
            if (session.getStartedAt() != null) {
                session.setDurationSeconds(
                        (int) java.time.Duration.between(session.getStartedAt(), session.getEndedAt()).getSeconds());
            }
        }

        TelemedicineSession updated = sessionRepository.save(session);

        eventProducer.publish("TELEMEDICINE", String.valueOf(updated.getId()), updated.getStatus().name(),
                KafkaTopics.TELEMEDICINE_EVENTS,
                TelemedicineEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .eventType(updated.getStatus().name())
                        .sessionId(updated.getId())
                        .appointmentId(updated.getAppointment().getId())
                        .patientId(updated.getAppointment().getPatient().getId())
                        .doctorId(updated.getAppointment().getDoctor().getUser().getId())
                        .occurredAt(LocalDateTime.now())
                        .build());

        boolean isDoctor = isDoctorForSession(updated, principal);
        return TelemedicineSessionResponse.fromEntity(updated, isDoctor);
    }

    @Transactional
    public TelemedicineSessionResponse saveCallNoteDraft(Long sessionId, String noteDraft, UserPrincipal principal) {
        TelemedicineSession session = getSessionWithAuthCheck(sessionId, principal);

        if (!isDoctorForSession(session, principal)) {
            throw new MediBookException("Only the doctor can save call notes",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        session.setCallNoteDraft(noteDraft);
        session.setDoctorReviewed(false);
        TelemedicineSession updated = sessionRepository.save(session);
        return TelemedicineSessionResponse.fromEntity(updated, true);
    }

    @Transactional
    public TelemedicineSessionResponse approveCallNote(Long sessionId, UserPrincipal principal) {
        TelemedicineSession session = getSessionWithAuthCheck(sessionId, principal);

        if (!isDoctorForSession(session, principal)) {
            throw new MediBookException("Only the doctor can approve call notes",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (session.getCallNoteDraft() == null || session.getCallNoteDraft().isBlank()) {
            throw new MediBookException("No call note draft to approve",
                    HttpStatus.BAD_REQUEST, "NO_DRAFT");
        }

        session.setDoctorReviewed(true);
        TelemedicineSession updated = sessionRepository.save(session);
        log.info("Call note approved by doctor for session [{}]", sessionId);
        return TelemedicineSessionResponse.fromEntity(updated, true);
    }

    @Transactional(readOnly = true)
    public TelemedicineSessionResponse getSession(Long sessionId, UserPrincipal principal) {
        TelemedicineSession session = getSessionWithAuthCheck(sessionId, principal);
        boolean isDoctor = isDoctorForSession(session, principal);
        return TelemedicineSessionResponse.fromEntity(session, isDoctor);
    }

    private static final long CHAT_WINDOW_PRE_MINUTES  = 10;
    private static final long CHAT_WINDOW_POST_MINUTES = 10;

    @Transactional
    public ChatMessageResponse sendChatMessage(Long sessionId, ChatMessageRequest req, UserPrincipal principal) {
        TelemedicineSession session = getSessionWithAuthCheck(sessionId, principal);

        if (session.getStatus() != TelemedicineSessionStatus.ACTIVE
                && session.getStatus() != TelemedicineSessionStatus.WAITING) {
            throw new MediBookException("Cannot send messages in session status: " + session.getStatus(),
                    HttpStatus.BAD_REQUEST, "SESSION_NOT_ACTIVE");
        }

        Appointment appt = session.getAppointment();

        // Physical consultations have no telemedicine chat channel.
        if (appt.getConsultationMedium() == ConsultationMedium.PHYSICAL) {
            throw new MediBookException(
                    "In-session chat is not available for physical consultations.",
                    HttpStatus.BAD_REQUEST, "PHYSICAL_CONSULTATION_NO_CHAT");
        }

        // Enforce the ±10-minute consultation window — mirrors TelemedicineCallService.ensureCallAllowed().
        LocalDateTime now        = LocalDateTime.now();
        LocalDateTime windowOpen = appt.getScheduledAt().minusMinutes(CHAT_WINDOW_PRE_MINUTES);
        LocalDateTime windowClose = (appt.getEndTime() != null
                ? appt.getEndTime()
                : appt.getScheduledAt().plusMinutes(appt.getDurationMins()))
                .plusMinutes(CHAT_WINDOW_POST_MINUTES);

        if (now.isBefore(windowOpen)) {
            long minutesUntil = java.time.Duration.between(now, windowOpen).toMinutes() + 1;
            throw new MediBookException(
                    "Chat opens " + CHAT_WINDOW_PRE_MINUTES + " minutes before the scheduled time. "
                            + "Please try again in " + minutesUntil + " minute(s).",
                    HttpStatus.CONFLICT, "SESSION_WINDOW_NOT_OPEN");
        }
        if (now.isAfter(windowClose)) {
            throw new MediBookException(
                    "The consultation window has closed. Chat is read-only after "
                            + CHAT_WINDOW_POST_MINUTES + " minutes past the scheduled end time.",
                    HttpStatus.GONE, "SESSION_WINDOW_EXPIRED");
        }

        User sender = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", principal.getId()));

        String role = principal.getAuthorities().iterator().next().getAuthority();
        Instant msgInstant = Instant.now();
        UUID messageId = UUID.randomUUID();

        // Write to MySQL first — inside the @Transactional boundary so a rollback
        // here does not leave a Cassandra orphan. Cassandra is written after commit.
        ChatMessage msg = ChatMessage.builder()
                .session(session)
                .sender(sender)
                .senderRole(role)
                .message(req.getMessage())
                .sentAt(java.time.LocalDateTime.ofInstant(msgInstant, java.time.ZoneOffset.UTC))
                .build();
        ChatMessageResponse response = ChatMessageResponse.fromEntity(chatMessageRepository.save(msg));

        // Mirror to Cassandra after the MySQL commit succeeds (eventual-consistency write).
        CassandraChatMessage cassandraMsg = CassandraChatMessage.builder()
                .sessionId(sessionId)
                .sentAt(msgInstant)
                .messageId(messageId)
                .senderId(sender.getId())
                .senderName(sender.getFullName())
                .senderRole(role)
                .message(req.getMessage())
                .system(false)
                .build();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cassandraChatRepo.save(cassandraMsg); }
            });
        } else {
            cassandraChatRepo.save(cassandraMsg);
        }

        return response;
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getChatHistory(Long sessionId, UserPrincipal principal) {
        getSessionWithAuthCheck(sessionId, principal);
        return chatMessageRepository.findBySessionIdOrderBySentAtAsc(sessionId)
                .stream().map(ChatMessageResponse::fromEntity).toList();
    }

    private TelemedicineSession getSessionWithAuthCheck(Long sessionId, UserPrincipal principal) {
        TelemedicineSession session = sessionRepository.findByIdWithDetails(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("TelemedicineSession", "id", sessionId));

        boolean isPatient = session.getAppointment().getPatient().getId().equals(principal.getId());
        boolean isDoctor  = session.getAppointment().getDoctor().getUser().getId().equals(principal.getId());
        boolean isAdmin   = principal.hasRole("ROLE_ADMIN");

        if (!isPatient && !isDoctor && !isAdmin) {
            throw new MediBookException("Not authorized to access this session",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return session;
    }

    private boolean isDoctorForSession(TelemedicineSession session, UserPrincipal principal) {
        return session.getAppointment().getDoctor().getUser().getId().equals(principal.getId());
    }

    private void validateTransition(TelemedicineSessionStatus current, TelemedicineSessionStatus requested) {
        boolean valid = switch (current) {
            case SCHEDULED -> requested == TelemedicineSessionStatus.WAITING
                    || requested == TelemedicineSessionStatus.CANCELLED;
            case WAITING   -> requested == TelemedicineSessionStatus.ACTIVE
                    || requested == TelemedicineSessionStatus.CANCELLED;
            case ACTIVE    -> requested == TelemedicineSessionStatus.COMPLETED
                    || requested == TelemedicineSessionStatus.FAILED;
            default        -> false;
        };
        if (!valid) {
            throw new MediBookException(
                    "Invalid session transition from " + current + " to " + requested,
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION");
        }
    }
}
