package com.medibook.ai.support.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fallback provider active when app.ai.support.provider=stub (the default).
 *
 * Returns static, safe answers — useful for local development without Ollama
 * and as a production circuit-breaker fallback.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.support.provider", havingValue = "stub", matchIfMissing = true)
public class StubSupportProvider implements SupportAiProvider {

    private static final String[] RESPONSES = {
        "I'm here to help with MediBook! You can book appointments by going to the Doctors section, " +
        "selecting a doctor, choosing an available time slot, and confirming your booking. " +
        "For other questions, feel free to ask — or contact our support team at support@medibook.com.",

        "Thanks for reaching out! If you need to manage appointments, head to your Dashboard. " +
        "You can also update your profile from the Settings page. What else can I help you with?",

        "I'd be happy to assist! You can view your upcoming appointments on the Dashboard, " +
        "cancel or reschedule from the appointment details, and find new doctors via the Search page.",

        "MediBook support here! Common tasks include booking appointments, viewing your medical history, " +
        "and managing notifications. Let me know what you'd like to do!"
    };

    @Override
    public String generate(String systemPrompt, String userMessage) {
        log.debug("StubSupportProvider responding (no real AI configured)");
        // Vary response based on message hash to avoid exact repeats
        int idx = Math.abs(userMessage.hashCode()) % RESPONSES.length;
        return RESPONSES[idx];
    }

    @Override
    public String providerId() {
        return "stub";
    }
}
