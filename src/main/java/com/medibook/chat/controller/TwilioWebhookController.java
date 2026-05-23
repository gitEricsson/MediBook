package com.medibook.chat.controller;

import com.medibook.chat.service.ChatService;
import com.medibook.chat.twilio.TwilioWebhookValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Receives inbound webhooks from Twilio Conversations.
 *
 * This endpoint is PUBLIC (no JWT) because Twilio cannot send Bearer tokens.
 * Security is enforced by validating the X-Twilio-Signature HMAC header.
 *
 * Configure in Twilio Console:
 *   Conversations → Webhook → POST https://api.medibook.com/api/v1/chat/twilio/webhook
 *   Events: onMessageAdded
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat/twilio")
@RequiredArgsConstructor
public class TwilioWebhookController {

    private final ChatService           chatService;
    private final TwilioWebhookValidator webhookValidator;

    @PostMapping(value = "/webhook", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<String> handleWebhook(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {

        if (!webhookValidator.isValid(request, request.getParameterMap())) {
            log.warn("Rejected invalid Twilio webhook from {}", request.getRemoteAddr());
            return ResponseEntity.status(403).body("Invalid signature");
        }

        log.debug("Twilio webhook received: EventType={} ConversationSid={}",
                params.get("EventType"), params.get("ConversationSid"));

        try {
            chatService.handleTwilioWebhook(new HashMap<>(params));
        } catch (Exception ex) {
            log.error("Twilio webhook processing error: {}", ex.getMessage(), ex);
        }

        return ResponseEntity.ok("");
    }
}
