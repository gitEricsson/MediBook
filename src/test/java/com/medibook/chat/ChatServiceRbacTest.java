package com.medibook.chat;

import com.medibook.ai.orchestration.AiOrchestrationService;
import com.medibook.chat.entity.AiConsentRecord;
import com.medibook.chat.entity.ChatConversation;
import com.medibook.chat.repository.*;
import com.medibook.chat.service.ChatService;
import com.medibook.chat.twilio.TwilioConversationsService;
import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.messaging.producer.ChatEventProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService — RBAC access control tests")
class ChatServiceRbacTest {

    @Mock ChatConversationRepository conversationRepo;
    @Mock ChatMessageRepository      messageRepo;
    @Mock AiDraftResponseRepository  draftRepo;
    @Mock UrgencyAlertRepository     urgencyRepo;
    @Mock AiConsentRecordRepository  consentRepo;
    @Mock AppointmentRepository      appointmentRepo;
    @Mock TwilioConversationsService twilioService;
    @Mock AiOrchestrationService     aiOrchestration;
    @Mock ChatEventProducer          eventProducer;
    @Mock NotificationService        notificationService;
    @Mock ObjectMapper               objectMapper;

    @InjectMocks
    ChatService chatService;

    private ChatConversation patientDoctorConv;
    private static final Long PATIENT_ID    = 100L;
    private static final Long DOCTOR_ID     = 200L;
    private static final Long STRANGER_ID   = 999L;
    private static final Long CONV_ID       = 1L;

    @BeforeEach
    void setUp() {
        patientDoctorConv = ChatConversation.builder()
                .id(CONV_ID)
                .twilioConversationSid("CH_TEST")
                .appointmentId(42L)
                .patientId(PATIENT_ID)
                .doctorId(DOCTOR_ID)
                .aiEnabled(true)
                .build();
    }

    @Test
    @DisplayName("Patient can get messages from own conversation")
    void patientAccessesOwnConversation() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV_ID)).thenReturn(java.util.List.of());

        chatService.getMessages(CONV_ID, PATIENT_ID); // must not throw
    }

    @Test
    @DisplayName("Stranger cannot access another patient's conversation")
    void strangerDeniedAccess() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));

        assertThatThrownBy(() -> chatService.getMessages(CONV_ID, STRANGER_ID))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("Doctor can access their assigned patient's conversation")
    void doctorAccessesAssignedConversation() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV_ID)).thenReturn(java.util.List.of());

        chatService.getMessages(CONV_ID, DOCTOR_ID); // must not throw
    }

    @Test
    @DisplayName("Patient cannot generate AI summary (doctor-only endpoint)")
    void patientCannotGenerateSummary() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));

        // Patient is not the doctor for this conversation
        assertThatThrownBy(() -> chatService.generateSummary(CONV_ID, PATIENT_ID))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("doctor");
    }

    @Test
    @DisplayName("Cannot acknowledge urgency alert for another doctor's conversation")
    void wrongDoctorCannotAckUrgency() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));

        // STRANGER_ID tries to ack urgency for conversation belonging to DOCTOR_ID
        assertThatThrownBy(() -> chatService.acknowledgeUrgency(CONV_ID, 1L, STRANGER_ID))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("AI is enabled on conversation after patient grants consent")
    void consentEnablesAi() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));
        when(consentRepo.findByPatientIdAndConversationId(PATIENT_ID, CONV_ID))
                .thenReturn(Optional.empty());
        when(consentRepo.save(any())).thenReturn(new AiConsentRecord());
        when(conversationRepo.save(any())).thenReturn(patientDoctorConv);

        chatService.grantConsent(CONV_ID, PATIENT_ID, true, "127.0.0.1", "TestAgent");

        verify(conversationRepo).save(argThat(c -> c.isAiEnabled()));
    }

    @Test
    @DisplayName("AI is disabled on conversation after patient revokes consent")
    void consentRevokeDisablesAi() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));
        when(consentRepo.findByPatientIdAndConversationId(PATIENT_ID, CONV_ID))
                .thenReturn(Optional.empty());
        when(consentRepo.save(any())).thenReturn(new AiConsentRecord());
        when(conversationRepo.save(any())).thenReturn(patientDoctorConv);

        chatService.grantConsent(CONV_ID, PATIENT_ID, false, "127.0.0.1", "TestAgent");

        verify(conversationRepo).save(argThat(c -> !c.isAiEnabled()));
    }

    @Test
    @DisplayName("Stranger cannot grant AI consent for another patient's conversation")
    void strangerCannotGrantConsent() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));

        assertThatThrownBy(() -> chatService.grantConsent(CONV_ID, STRANGER_ID, true, "", ""))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("Draft not in PENDING state cannot be approved")
    void nonPendingDraftCannotBeApproved() {
        when(conversationRepo.findById(CONV_ID)).thenReturn(Optional.of(patientDoctorConv));
        var draft = com.medibook.chat.entity.AiDraftResponse.builder()
                .id(1L).conversationId(CONV_ID).doctorId(DOCTOR_ID)
                .status(com.medibook.chat.entity.AiDraftResponse.DraftStatus.SENT)
                .draftBody("old draft").promptVersion("v1").modelUsed("stub")
                .build();
        when(draftRepo.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> chatService.approveDraft(CONV_ID, 1L, null, DOCTOR_ID))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("PENDING");
    }
}
