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

    @Override
    public String generate(String systemPrompt, String userMessage) {
        log.debug("StubSupportProvider responding (no real AI configured)");
        return "I'm here to help with MediBook! You can book appointments by going to the Doctors section, " +
               "selecting a doctor, choosing an available time slot, and confirming your booking. " +
               "For other questions, feel free to ask — or contact our support team at support@medibook.com.";
    }

    @Override
    public String providerId() {
        return "stub";
    }
}
