package com.medibook.domain.telemedicine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.chat.dto.ConversationResponse;
import com.medibook.chat.entity.ChatConversation;
import com.medibook.chat.entity.ChatMessage;
import com.medibook.chat.repository.ChatConversationRepository;
import com.medibook.chat.repository.ChatMessageRepository;
import com.medibook.chat.service.ChatService;
import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.telemedicine.dto.CallParticipantRequest;
import com.medibook.domain.telemedicine.dto.VideoCallResponse;
import com.medibook.domain.telemedicine.dto.VideoTokenResponse;
import com.medibook.domain.telemedicine.entity.*;
import com.medibook.domain.telemedicine.repository.CallParticipantRepository;
import com.medibook.domain.telemedicine.repository.TelemedicineSessionRepository;
import com.medibook.messaging.event.ChatEvent;
import com.medibook.messaging.producer.ChatEventProducer;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemedicineCallService {

    private static final List<TelemedicineSessionStatus> ACTIVE_STATUSES = List.of(
            TelemedicineSessionStatus.CREATED,
            TelemedicineSessionStatus.RINGING,
            TelemedicineSessionStatus.WAITING,
            TelemedicineSessionStatus.ACTIVE
    );

    private final TelemedicineSessionRepository sessionRepository;
    private final CallParticipantRepository participantRepository;
    private final AppointmentRepository appointmentRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatService chatService;
    private final TwilioVideoService twilioVideoService;
    private final TwilioTokenService twilioTokenService;
    private final ChatEventProducer chatEventProducer;
    private final ObjectMapper objectMapper;

    @Transactional
    public VideoCallResponse startVideoCall(Long appointmentId, UserPrincipal principal) {
        Appointment appointment = loadAppointmentAndAuthorize(appointmentId, principal);
        ensureCallAllowed(appointment);

        TelemedicineSession session = sessionRepository
                .findFirstByAppointmentIdAndStatusIn(appointmentId, ACTIVE_STATUSES)
                .orElseGet(() -> createSession(appointment, principal));

        if (session.getStartedByUserId() == null) {
            session.setStartedByUserId(principal.getId());
        }
        if (session.getStartedAt() == null) {
            session.setStartedAt(LocalDateTime.now());
        }
        if (session.getStatus() == TelemedicineSessionStatus.CREATED
                || session.getStatus() == TelemedicineSessionStatus.SCHEDULED) {
            session.setStatus(TelemedicineSessionStatus.RINGING);
        }

        session = sessionRepository.save(session);
        upsertInvitedParticipants(session, appointment);
        saveSystemMessage(session, principal.getId(), "VIDEO_CALL_STARTED", "Video call started");
        publishEvent("TELEMEDICINE_CALL_STARTED", session);
        return responseWithToken(session, principal);
    }

    @Transactional(readOnly = true)
    public VideoTokenResponse getToken(Long sessionId, UserPrincipal principal) {
        TelemedicineSession session = loadSessionAndAuthorize(sessionId, principal);
        TwilioTokenService.TokenResult token = twilioTokenService.generateVideoToken(
                identity(principal.getId()),
                session.getTwilioRoomName());
        return VideoTokenResponse.builder()
                .token(token.token())
                .roomName(session.getTwilioRoomName())
                .identity(identity(principal.getId()))
                .expiresAt(token.expiresAt())
                .build();
    }

    @Transactional
    public VideoCallResponse join(Long sessionId, CallParticipantRequest request, UserPrincipal principal) {
        TelemedicineSession session = loadSessionAndAuthorize(sessionId, principal);
        CallParticipant participant = participantRepository
                .findBySessionIdAndUserId(sessionId, principal.getId())
                .orElseGet(() -> newParticipant(session, principal.getId(), roleFor(session, principal.getId())));

        participant.setJoinedAt(LocalDateTime.now());
        participant.setLeftAt(null);
        participant.setCameraEnabled(request == null || request.cameraEnabled() == null || request.cameraEnabled());
        participant.setMicrophoneEnabled(request == null || request.microphoneEnabled() == null || request.microphoneEnabled());
        participant.setConnectionStatus(CallConnectionStatus.JOINED);
        participantRepository.save(participant);

        if (session.getAcceptedAt() == null && !principal.getId().equals(session.getStartedByUserId())) {
            session.setAcceptedAt(LocalDateTime.now());
        }
        session.setStatus(TelemedicineSessionStatus.ACTIVE);
        sessionRepository.save(session);

        saveSystemMessage(session, principal.getId(), "VIDEO_CALL_JOINED",
                participant.getRole() == CallParticipantRole.DOCTOR ? "Doctor joined" : "Patient joined");
        return responseWithToken(session, principal);
    }

    @Transactional
    public VideoCallResponse leave(Long sessionId, CallParticipantRequest request, UserPrincipal principal) {
        TelemedicineSession session = loadSessionAndAuthorize(sessionId, principal);
        CallParticipant participant = participantRepository
                .findBySessionIdAndUserId(sessionId, principal.getId())
                .orElseGet(() -> newParticipant(session, principal.getId(), roleFor(session, principal.getId())));

        participant.setLeftAt(LocalDateTime.now());
        participant.setCameraEnabled(request != null && Boolean.TRUE.equals(request.cameraEnabled()));
        participant.setMicrophoneEnabled(request == null || request.microphoneEnabled() == null || request.microphoneEnabled());
        participant.setConnectionStatus(CallConnectionStatus.LEFT);
        participantRepository.save(participant);

        saveSystemMessage(session, principal.getId(), "VIDEO_CALL_LEFT",
                participant.getRole() == CallParticipantRole.DOCTOR ? "Doctor left" : "Patient left");

        if (allJoinedParticipantsLeft(session.getId())) {
            endSession(session, "all participants left", principal.getId());
        }
        return response(sessionRepository.save(session));
    }

    @Transactional
    public VideoCallResponse endCall(Long sessionId, String reason, UserPrincipal principal) {
        TelemedicineSession session = loadSessionAndAuthorize(sessionId, principal);
        endSession(session, reason == null || reason.isBlank() ? "ended by participant" : reason, principal.getId());
        publishEvent("TELEMEDICINE_CALL_ENDED", session);
        return response(sessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public VideoCallResponse getActiveCall(Long appointmentId, UserPrincipal principal) {
        loadAppointmentAndAuthorize(appointmentId, principal);
        return sessionRepository.findFirstByAppointmentIdAndStatusIn(appointmentId, ACTIVE_STATUSES)
                .map(this::response)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public VideoCallResponse getSession(Long sessionId, UserPrincipal principal) {
        return response(loadSessionAndAuthorize(sessionId, principal));
    }

    private TelemedicineSession createSession(Appointment appointment, UserPrincipal principal) {
        ConversationResponse conversation = chatService.createConversation(appointment.getId(), principal.getId());
        String roomName = "medibook-appt-" + appointment.getId() + "-session-" + UUID.randomUUID();
        TwilioVideoService.RoomResult room = twilioVideoService.createRoom(roomName);

        TelemedicineSession session = TelemedicineSession.builder()
                .appointment(appointment)
                .patientId(appointment.getPatient().getId())
                .doctorId(appointment.getDoctor().getUser().getId())
                .chatConversationId(conversation.id())
                .status(TelemedicineSessionStatus.CREATED)
                .roomId(room.roomSid())
                .twilioRoomSid(room.roomSid())
                .twilioRoomName(room.roomName())
                .startedByUserId(principal.getId())
                .startedAt(LocalDateTime.now())
                .patientConsent(false)
                .build();
        return sessionRepository.save(session);
    }

    private Appointment loadAppointmentAndAuthorize(Long appointmentId, UserPrincipal principal) {
        Appointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", appointmentId));

        boolean isPatient = appointment.getPatient().getId().equals(principal.getId());
        boolean isDoctor = appointment.getDoctor().getUser().getId().equals(principal.getId());
        if (!isPatient && !isDoctor) {
            throw new MediBookException("Not authorized to access this call",
                    HttpStatus.FORBIDDEN, "CALL_ACCESS_DENIED");
        }
        return appointment;
    }

    private void ensureCallAllowed(Appointment appointment) {
        if (appointment.getStatus() == AppointmentStatus.CANCELLED
                || appointment.getStatus() == AppointmentStatus.COMPLETED
                || appointment.getStatus() == AppointmentStatus.NO_SHOW) {
            throw new MediBookException("Telemedicine call is not allowed for this appointment",
                    HttpStatus.BAD_REQUEST, "TELEMEDICINE_NOT_ALLOWED");
        }
    }

    private TelemedicineSession loadSessionAndAuthorize(Long sessionId, UserPrincipal principal) {
        TelemedicineSession session = sessionRepository.findByIdWithDetails(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("TelemedicineSession", "id", sessionId));

        boolean isPatient = session.getAppointment().getPatient().getId().equals(principal.getId());
        boolean isDoctor = session.getAppointment().getDoctor().getUser().getId().equals(principal.getId());
        boolean isAdmin = principal.hasRole("ROLE_ADMIN") || principal.hasRole("ROLE_SUPER_ADMIN");
        if (!isPatient && !isDoctor && !isAdmin) {
            throw new MediBookException("Not authorized to access this call",
                    HttpStatus.FORBIDDEN, "CALL_ACCESS_DENIED");
        }
        return session;
    }

    private void endSession(TelemedicineSession session, String reason, Long actorUserId) {
        LocalDateTime endedAt = LocalDateTime.now();
        session.setEndedAt(endedAt);
        session.setEndReason(reason);
        session.setStatus(TelemedicineSessionStatus.ENDED);
        if (session.getStartedAt() != null) {
            session.setDurationSeconds((int) Duration.between(session.getStartedAt(), endedAt).getSeconds());
        }
        twilioVideoService.completeRoom(session.getTwilioRoomSid() != null
                ? session.getTwilioRoomSid()
                : session.getTwilioRoomName());
        saveSystemMessage(session, actorUserId, "VIDEO_CALL_ENDED", callEndedMessage(session));
    }

    private void upsertInvitedParticipants(TelemedicineSession session, Appointment appointment) {
        participantRepository.findBySessionIdAndUserId(session.getId(), appointment.getPatient().getId())
                .orElseGet(() -> participantRepository.save(newParticipant(
                        session, appointment.getPatient().getId(), CallParticipantRole.PATIENT)));
        participantRepository.findBySessionIdAndUserId(session.getId(), appointment.getDoctor().getUser().getId())
                .orElseGet(() -> participantRepository.save(newParticipant(
                        session, appointment.getDoctor().getUser().getId(), CallParticipantRole.DOCTOR)));
    }

    private CallParticipant newParticipant(TelemedicineSession session, Long userId, CallParticipantRole role) {
        return CallParticipant.builder()
                .session(session)
                .userId(userId)
                .role(role)
                .connectionStatus(CallConnectionStatus.INVITED)
                .build();
    }

    private boolean allJoinedParticipantsLeft(Long sessionId) {
        List<CallParticipant> participants = participantRepository.findBySessionId(sessionId);
        List<CallParticipant> joined = participants.stream()
                .filter(p -> p.getJoinedAt() != null)
                .toList();
        return !joined.isEmpty() && joined.stream().allMatch(p -> p.getLeftAt() != null);
    }

    private CallParticipantRole roleFor(TelemedicineSession session, Long userId) {
        if (session.getAppointment().getPatient().getId().equals(userId)) {
            return CallParticipantRole.PATIENT;
        }
        return CallParticipantRole.DOCTOR;
    }

    private VideoCallResponse responseWithToken(TelemedicineSession session, UserPrincipal principal) {
        TwilioTokenService.TokenResult token = twilioTokenService.generateVideoToken(
                identity(principal.getId()), session.getTwilioRoomName());
        return responseBuilder(session)
                .token(token.token())
                .identity(identity(principal.getId()))
                .tokenExpiresAt(token.expiresAt())
                .build();
    }

    private VideoCallResponse response(TelemedicineSession session) {
        return responseBuilder(session).build();
    }

    private VideoCallResponse.VideoCallResponseBuilder responseBuilder(TelemedicineSession session) {
        return VideoCallResponse.builder()
                .sessionId(session.getId())
                .appointmentId(session.getAppointment().getId())
                .conversationId(session.getChatConversationId())
                .patientId(session.getAppointment().getPatient().getId())
                .doctorId(session.getAppointment().getDoctor().getUser().getId())
                .status(session.getStatus())
                .twilioRoomSid(session.getTwilioRoomSid())
                .roomName(session.getTwilioRoomName())
                .startedAt(session.getStartedAt())
                .acceptedAt(session.getAcceptedAt())
                .endedAt(session.getEndedAt())
                .durationSeconds(session.getDurationSeconds());
    }

    private String identity(Long userId) {
        return "user-" + userId;
    }

    private void saveSystemMessage(TelemedicineSession session, Long actorUserId, String type, String message) {
        Long conversationId = resolveConversationId(session, actorUserId);
        try {
            String metadata = objectMapper.writeValueAsString(Map.of(
                    "type", type,
                    "sessionId", session.getId(),
                    "roomName", session.getTwilioRoomName() == null ? "" : session.getTwilioRoomName()
            ));
            ChatMessage chatMessage = ChatMessage.builder()
                    .conversationId(conversationId)
                    .twilioMessageSid("SYS_CALL_" + UUID.randomUUID().toString().replace("-", ""))
                    .senderId(actorUserId)
                    .senderRole(ChatMessage.SenderRole.SYSTEM)
                    .body(message)
                    .aiGenerated(false)
                    .metadataJson(metadata)
                    .build();
            chatMessageRepository.save(chatMessage);
        } catch (Exception ex) {
            log.warn("Failed to persist call system message: {}", ex.getMessage());
        }
    }

    private Long resolveConversationId(TelemedicineSession session, Long actorUserId) {
        if (session.getChatConversationId() != null) {
            return session.getChatConversationId();
        }
        ChatConversation conversation = conversationRepository.findByAppointmentId(session.getAppointment().getId())
                .orElseGet(() -> {
                    ConversationResponse created = chatService.createConversation(session.getAppointment().getId(), actorUserId);
                    return conversationRepository.findById(created.id())
                            .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", created.id()));
                });
        session.setChatConversationId(conversation.getId());
        return conversation.getId();
    }

    private String callEndedMessage(TelemedicineSession session) {
        if (session.getDurationSeconds() == null || session.getDurationSeconds() < 60) {
            return "Call ended";
        }
        int minutes = Math.max(1, session.getDurationSeconds() / 60);
        return "Call ended after " + minutes + " minute" + (minutes == 1 ? "" : "s");
    }

    private void publishEvent(String eventType, TelemedicineSession session) {
        chatEventProducer.publishChatEvent(ChatEvent.builder()
                .eventType(eventType)
                .conversationId(session.getChatConversationId())
                .appointmentId(session.getAppointment().getId())
                .patientId(session.getAppointment().getPatient().getId())
                .doctorId(session.getAppointment().getDoctor().getUser().getId())
                .build());
    }
}
