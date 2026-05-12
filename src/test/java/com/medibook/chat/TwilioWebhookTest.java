package com.medibook.chat;

import com.medibook.chat.entity.ChatConversation;
import com.medibook.chat.repository.AiConsentRecordRepository;
import com.medibook.chat.repository.ChatConversationRepository;
import com.medibook.chat.repository.ChatMessageRepository;
import com.medibook.chat.repository.UrgencyAlertRepository;
import com.medibook.chat.service.ChatService;
import com.medibook.chat.twilio.TwilioWebhookValidator;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Twilio Webhook — controller integration tests")
class TwilioWebhookTest {

    @Mock private ChatService             chatService;
    @Mock private TwilioWebhookValidator  webhookValidator;

    private com.medibook.chat.controller.TwilioWebhookController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new com.medibook.chat.controller.TwilioWebhookController(chatService, webhookValidator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Valid webhook with valid signature returns 200")
    void validWebhookReturns200() throws Exception {
        when(webhookValidator.isValid(any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/chat/twilio/webhook")
                        .contentType("application/x-www-form-urlencoded")
                        .param("EventType", "onMessageAdded")
                        .param("ConversationSid", "CH123")
                        .param("Author", "100")
                        .param("Body", "Hello doctor")
                        .param("MessageSid", "IM456"))
                .andExpect(status().isOk());

        verify(chatService).handleTwilioWebhook(any());
    }

    @Test
    @DisplayName("Invalid signature returns 403 and does not process")
    void invalidSignatureReturns403() throws Exception {
        when(webhookValidator.isValid(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/chat/twilio/webhook")
                        .contentType("application/x-www-form-urlencoded")
                        .param("EventType", "onMessageAdded")
                        .param("ConversationSid", "CH_FAKE")
                        .param("Body", "malicious payload"))
                .andExpect(status().isForbidden());

        verify(chatService, never()).handleTwilioWebhook(any());
    }

    @Test
    @DisplayName("Webhook processing error still returns 200 to prevent Twilio retry loop")
    void processingErrorStillReturns200() throws Exception {
        when(webhookValidator.isValid(any(), any())).thenReturn(true);
        doThrow(new RuntimeException("unexpected error")).when(chatService).handleTwilioWebhook(any());

        // Should NOT throw — Twilio needs 200 to prevent retry storms
        mockMvc.perform(post("/api/v1/chat/twilio/webhook")
                        .contentType("application/x-www-form-urlencoded")
                        .param("EventType", "onMessageAdded")
                        .param("ConversationSid", "CH123")
                        .param("Body", "Hello"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Non-onMessageAdded events are ignored silently")
    void nonMessageEventIgnored() throws Exception {
        when(webhookValidator.isValid(any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/chat/twilio/webhook")
                        .contentType("application/x-www-form-urlencoded")
                        .param("EventType", "onConversationAdded")
                        .param("ConversationSid", "CH123"))
                .andExpect(status().isOk());

        verify(chatService).handleTwilioWebhook(any()); // called, but ChatService ignores non-message events
    }
}
