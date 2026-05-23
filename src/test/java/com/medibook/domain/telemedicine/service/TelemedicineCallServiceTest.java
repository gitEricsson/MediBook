package com.medibook.domain.telemedicine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.chat.dto.ConversationResponse;
import com.medibook.chat.entity.ChatMessage;
import com.medibook.chat.repository.ChatConversationRepository;
import com.medibook.chat.repository.ChatMessageRepository;
import com.medibook.chat.service.ChatService;
import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.telemedicine.dto.CallParticipantRequest;
import com.medibook.domain.telemedicine.dto.VideoCallResponse;
import com.medibook.domain.telemedicine.dto.VideoTokenResponse;
import com.medibook.domain.telemedicine.entity.CallParticipant;
import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import com.medibook.domain.telemedicine.repository.CallParticipantRepository;
import com.medibook.domain.telemedicine.repository.TelemedicineSessionRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.messaging.event.ChatEvent;
import com.medibook.messaging.producer.ChatEventProducer;
import com.medibook.infrastructure.metrics.EmergencyMetrics;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemedicineCallServiceTest {

    @Mock private TelemedicineSessionRepository sessionRepository;
    @Mock private CallParticipantRepository participantRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private ChatConversationRepository conversationRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private ChatService chatService;
    @Mock private TwilioVideoService twilioVideoService;
    @Mock private TwilioTokenService twilioTokenService;
    @Mock private ChatEventProducer chatEventProducer;
    @Mock private EmergencyMetrics emergencyMetrics;
    @Mock private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private TelemedicineCallService service;
    private User patient;
    private User doctorUser;
    private User unrelatedUser;
    private Appointment appointment;
    private UserPrincipal patientPrincipal;
    private UserPrincipal doctorPrincipal;
    private UserPrincipal unrelatedPrincipal;

    @BeforeEach
    void setUp() {
        service = new TelemedicineCallService(
                sessionRepository,
                participantRepository,
                appointmentRepository,
                conversationRepository,
                chatMessageRepository,
                chatService,
                twilioVideoService,
                twilioTokenService,
                chatEventProducer,
                new ObjectMapper(),
                emergencyMetrics,
                meterRegistry);

        patient = user(10L, Role.ROLE_PATIENT);
        doctorUser = user(20L, Role.ROLE_DOCTOR);
        unrelatedUser = user(30L, Role.ROLE_PATIENT);
        Doctor doctor = Doctor.builder().id(200L).user(doctorUser).licenseNumber("LIC-20").build();
        appointment = Appointment.builder()
                .id(100L)
                .patient(patient)
                .doctor(doctor)
                .status(AppointmentStatus.CONFIRMED)
                .scheduledAt(LocalDateTime.now().plusMinutes(15))
                .build();

        patientPrincipal = UserPrincipal.fromUser(patient);
        doctorPrincipal = UserPrincipal.fromUser(doctorUser);
        unrelatedPrincipal = UserPrincipal.fromUser(unrelatedUser);
    }

    @Test
    void startsCallAsPatientAndCreatesChatAndKafkaEvents() {
        arrangeNewCall(patient.getId());

        VideoCallResponse response = service.startVideoCall(appointment.getId(), patientPrincipal);

        assertThat(response.status()).isEqualTo(TelemedicineSessionStatus.RINGING);
        assertThat(response.identity()).isEqualTo("user-10");
        assertThat(response.token()).isEqualTo("video-token");

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getConversationId()).isEqualTo(900L);
        assertThat(messageCaptor.getValue().getSenderRole()).isEqualTo(ChatMessage.SenderRole.SYSTEM);
        assertThat(messageCaptor.getValue().getBody()).isEqualTo("Video call started");

        ArgumentCaptor<ChatEvent> eventCaptor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(chatEventProducer).publishChatEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("TELEMEDICINE_CALL_STARTED");
        assertThat(eventCaptor.getValue().getAppointmentId()).isEqualTo(appointment.getId());
        verify(participantRepository, times(2)).save(any(CallParticipant.class));
    }

    @Test
    void startsCallAsDoctor() {
        arrangeNewCall(doctorUser.getId());

        VideoCallResponse response = service.startVideoCall(appointment.getId(), doctorPrincipal);

        assertThat(response.status()).isEqualTo(TelemedicineSessionStatus.RINGING);
        assertThat(response.identity()).isEqualTo("user-20");
        verify(chatService).createConversation(appointment.getId(), doctorUser.getId());
    }

    @Test
    void rejectsUnrelatedUserStartingCall() {
        when(appointmentRepository.findByIdWithDetails(appointment.getId())).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> service.startVideoCall(appointment.getId(), unrelatedPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Not authorized");

        verifyNoInteractions(twilioVideoService, chatService, chatMessageRepository, chatEventProducer);
    }

    @Test
    void generatesTokenOnlyForAllowedParticipant() {
        TelemedicineSession session = existingSession(TelemedicineSessionStatus.RINGING);
        when(sessionRepository.findByIdWithDetails(session.getId())).thenReturn(Optional.of(session));
        when(twilioTokenService.generateVideoToken("user-10", session.getTwilioRoomName()))
                .thenReturn(new TwilioTokenService.TokenResult("allowed-token", Instant.now().plusSeconds(900)));

        VideoTokenResponse token = service.getToken(session.getId(), patientPrincipal);

        assertThat(token.identity()).isEqualTo("user-10");
        assertThat(token.token()).isEqualTo("allowed-token");

        assertThatThrownBy(() -> service.getToken(session.getId(), unrelatedPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Not authorized");
    }

    @Test
    void joinMarksParticipantAndCreatesChatSystemEvent() {
        TelemedicineSession session = existingSession(TelemedicineSessionStatus.RINGING);
        when(sessionRepository.findByIdWithDetails(session.getId())).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionIdAndUserId(session.getId(), doctorUser.getId())).thenReturn(Optional.empty());
        when(twilioTokenService.generateVideoToken("user-20", session.getTwilioRoomName()))
                .thenReturn(new TwilioTokenService.TokenResult("join-token", Instant.now().plusSeconds(900)));
        when(sessionRepository.save(any(TelemedicineSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VideoCallResponse response = service.join(
                session.getId(),
                new CallParticipantRequest(false, true),
                doctorPrincipal);

        assertThat(response.status()).isEqualTo(TelemedicineSessionStatus.ACTIVE);
        verify(participantRepository).save(argThat(participant ->
                participant.getUserId().equals(doctorUser.getId())
                        && !participant.isCameraEnabled()
                        && participant.isMicrophoneEnabled()));
        verify(chatMessageRepository).save(argThat(message ->
                message.getSenderRole() == ChatMessage.SenderRole.SYSTEM
                        && message.getBody().equals("Doctor joined")));
    }

    @Test
    void endingCallCalculatesDurationAndPublishesKafkaEvent() {
        TelemedicineSession session = existingSession(TelemedicineSessionStatus.ACTIVE);
        session.setStartedAt(LocalDateTime.now().minusSeconds(125));
        when(sessionRepository.findByIdWithDetails(session.getId())).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(TelemedicineSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VideoCallResponse response = service.endCall(session.getId(), "consultation complete", doctorPrincipal);

        assertThat(response.status()).isEqualTo(TelemedicineSessionStatus.ENDED);
        assertThat(response.durationSeconds()).isGreaterThanOrEqualTo(120);
        verify(twilioVideoService).completeRoom(session.getTwilioRoomSid());
        verify(chatMessageRepository).save(argThat(message ->
                message.getSenderRole() == ChatMessage.SenderRole.SYSTEM
                        && message.getBody().startsWith("Call ended after 2 minute")));
        verify(chatEventProducer).publishChatEvent(argThat(event ->
                event.getEventType().equals("TELEMEDICINE_CALL_ENDED")
                        && event.getConversationId().equals(session.getChatConversationId())));
    }

    private void arrangeNewCall(Long starterUserId) {
        when(appointmentRepository.findByIdWithDetails(appointment.getId())).thenReturn(Optional.of(appointment));
        when(sessionRepository.findFirstByAppointmentIdAndStatusIn(eq(appointment.getId()), anyCollection()))
                .thenReturn(Optional.empty());
        when(chatService.createConversation(appointment.getId(), starterUserId))
                .thenReturn(new ConversationResponse(
                        900L,
                        "CH900",
                        appointment.getId(),
                        patient.getId(),
                        doctorUser.getId(),
                        "ACTIVE",
                        false,
                        LocalDateTime.now()));
        when(twilioVideoService.createRoom(anyString()))
                .thenReturn(new TwilioVideoService.RoomResult("RM123", "medibook-appt-100-session-test"));
        when(twilioTokenService.generateVideoToken(anyString(), eq("medibook-appt-100-session-test")))
                .thenReturn(new TwilioTokenService.TokenResult("video-token", Instant.now().plusSeconds(900)));
        when(sessionRepository.save(any(TelemedicineSession.class))).thenAnswer(invocation -> {
            TelemedicineSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(500L);
            }
            return session;
        });
        when(participantRepository.findBySessionIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(participantRepository.save(any(CallParticipant.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private TelemedicineSession existingSession(TelemedicineSessionStatus status) {
        return TelemedicineSession.builder()
                .id(500L)
                .appointment(appointment)
                .patientId(patient.getId())
                .doctorId(doctorUser.getId())
                .chatConversationId(900L)
                .status(status)
                .twilioRoomSid("RM123")
                .twilioRoomName("medibook-appt-100-session-500")
                .startedByUserId(patient.getId())
                .startedAt(LocalDateTime.now().minusSeconds(30))
                .build();
    }

    private User user(Long id, Role role) {
        return User.builder()
                .id(id)
                .email("user-" + id + "@medibook.test")
                .password("secret")
                .firstName("User")
                .lastName(String.valueOf(id))
                .role(role)
                .enabled(true)
                .isActive(true)
                .build();
    }
}
