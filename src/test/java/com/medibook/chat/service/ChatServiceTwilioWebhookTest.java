package com.medibook.chat.service;

import com.medibook.chat.entity.ChatMessage;
import com.medibook.chat.repository.ChatMessageRepository;
import com.medibook.chat.twilio.TwilioConversationsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the Twilio webhook handler is idempotent on MessageSid and ignores
 * AI-author / non-onMessageAdded events. Service has many dependencies — all
 * mocked via @InjectMocks, only the messageRepo branch is exercised here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService.handleTwilioWebhook — Idempotency")
class ChatServiceTwilioWebhookTest {

    @Mock com.medibook.chat.repository.ChatConversationRepository conversationRepo;
    @Mock ChatMessageRepository messageRepo;
    @Mock com.medibook.chat.repository.AiDraftResponseRepository draftRepo;
    @Mock com.medibook.chat.repository.UrgencyAlertRepository urgencyRepo;
    @Mock com.medibook.chat.repository.AiConsentRecordRepository consentRepo;
    @Mock com.medibook.domain.appointment.repository.AppointmentRepository appointmentRepo;
    @Mock TwilioConversationsService twilioService;
    @Mock com.medibook.ai.orchestration.AiOrchestrationService aiOrchestration;
    @Mock com.medibook.messaging.producer.ChatEventProducer eventProducer;
    @Mock com.medibook.domain.notification.service.NotificationService notificationService;
    @Mock org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks ChatService chatService;

    @Test
    @DisplayName("duplicate MessageSid is short-circuited — no re-insert")
    void duplicateMessageSidSkipped() {
        when(messageRepo.findByTwilioMessageSid("MSG-DUP")).thenReturn(Optional.of(new ChatMessage()));

        chatService.handleTwilioWebhook(Map.of(
                "EventType",        "onMessageAdded",
                "ConversationSid",  "CONV-1",
                "Author",           "50",
                "Body",             "hello",
                "MessageSid",       "MSG-DUP"));

        verify(messageRepo, never()).save(any());
    }

    @Test
    @DisplayName("AI author messages are ignored to avoid loops")
    void aiAuthorIgnored() {
        chatService.handleTwilioWebhook(Map.of(
                "EventType",        "onMessageAdded",
                "ConversationSid",  "CONV-1",
                "Author",           TwilioConversationsService.AI_IDENTITY,
                "Body",             "AI reply",
                "MessageSid",       "MSG-2"));

        verify(messageRepo, never()).save(any());
        verify(messageRepo, never()).findByTwilioMessageSid(any());
    }

    @Test
    @DisplayName("non onMessageAdded event types are ignored")
    void otherEventTypesIgnored() {
        chatService.handleTwilioWebhook(Map.of("EventType", "onConversationUpdated"));
        verify(messageRepo, never()).save(any());
    }
}
