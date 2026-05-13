package com.medibook.chat.twilio;

import com.twilio.Twilio;
import com.twilio.rest.conversations.v1.Conversation;
import com.twilio.rest.conversations.v1.conversation.Message;
import com.twilio.rest.conversations.v1.conversation.Participant;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Wraps the Twilio Conversations REST API.
 *
 * HIPAA NOTE: Twilio Conversations is BAA-eligible for healthcare customers.
 * Obtain a BAA from Twilio before handling PHI in conversations.
 * Reference: https://www.twilio.com/en-us/hipaa
 *
 * The AI identity is a Conversation Service Scoped Identity.
 * Set TWILIO_AI_IDENTITY to the Twilio Conversations identity for the AI participant.
 */
@Slf4j
@Service
public class TwilioConversationsService {

    /** Twilio identity string for the AI assistant participant */
    public static final String AI_IDENTITY = "medibook-ai-assistant";

    @Value("${twilio.account-sid:#{null}}")
    private String accountSid;

    @Value("${twilio.auth-token:#{null}}")
    private String authToken;

    @Value("${twilio.conversations.service-sid:#{null}}")
    private String conversationServiceSid;

    private boolean configured;

    @PostConstruct
    public void init() {
        configured = accountSid != null && !accountSid.isBlank()
                && authToken != null && !authToken.isBlank();
        if (configured) {
            Twilio.init(accountSid, authToken);
            log.info("Twilio Conversations initialized — service SID configured: {}", conversationServiceSid != null);
        } else {
            log.warn("Twilio not configured — running in stub mode. " +
                     "Set twilio.account-sid and twilio.auth-token to enable real conversations.");
        }
    }

    /**
     * Create a new Twilio Conversation for an appointment.
     * Adds the AI assistant as a participant.
     * @return Twilio Conversation SID
     */
    public String createConversation(String friendlyName) {
        if (!configured) {
            String stubSid = "CH_STUB_" + System.currentTimeMillis();
            log.info("[STUB] Created conversation: {}", stubSid);
            return stubSid;
        }

        try {
            Conversation conv = Conversation.creator()
                    .setFriendlyName(friendlyName)
                    .create();

            // Add AI assistant as a participant
            Participant.creator(conv.getSid())
                    .setIdentity(AI_IDENTITY)
                    .create();

            log.info("Created Twilio conversation: {}", conv.getSid());
            return conv.getSid();

        } catch (Exception ex) {
            log.error("Failed to create Twilio conversation: {}", ex.getMessage());
            throw new RuntimeException("Failed to create conversation", ex);
        }
    }

    /**
     * Post a message to the conversation as the AI assistant.
     * All AI messages include the "ai_generated:true" attribute.
     * @return Twilio Message SID
     */
    public String postAiMessage(String conversationSid, String body) {
        if (!configured) {
            String stubSid = "IM_STUB_AI_" + System.currentTimeMillis();
            log.info("[STUB] AI message posted to {}: {} (SID: {})", conversationSid,
                    body.substring(0, Math.min(50, body.length())), stubSid);
            return stubSid;
        }

        try {
            Message msg = Message.creator(conversationSid)
                    .setAuthor(AI_IDENTITY)
                    .setBody(body)
                    .setAttributes("{\"ai_generated\":true,\"label\":\"AI-generated\"}")
                    .create();

            log.info("AI message posted to conversation {}: SID={}", conversationSid, msg.getSid());
            return msg.getSid();

        } catch (Exception ex) {
            log.error("Failed to post AI message to {}: {}", conversationSid, ex.getMessage());
            throw new RuntimeException("Failed to post AI message", ex);
        }
    }

    /**
     * Post a message on behalf of a doctor (approved draft).
     * @return Twilio Message SID
     */
    public String postDoctorMessage(String conversationSid, String body, String doctorIdentity) {
        if (!configured) {
            String stubSid = "IM_STUB_DOC_" + System.currentTimeMillis();
            log.info("[STUB] Doctor message posted by {} to {}", doctorIdentity, conversationSid);
            return stubSid;
        }

        try {
            Message msg = Message.creator(conversationSid)
                    .setAuthor(doctorIdentity)
                    .setBody(body)
                    .create();
            return msg.getSid();
        } catch (Exception ex) {
            log.error("Failed to post doctor message: {}", ex.getMessage());
            throw new RuntimeException("Failed to post doctor message", ex);
        }
    }

    public boolean isConfigured() { return configured; }
}
