package com.medibook.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Validates that the AI and telemedicine provider env vars are consistent.
 *
 * <p>Background: both <code>app.ai.support.provider</code>,
 * <code>app.intelligence.nlp-provider</code> and <code>app.telemedicine.provider</code>
 * default to <code>stub</code>. The stub returns canned strings; in prod that's a silent
 * regression. This validator runs on startup and fails loudly:
 *
 * <ul>
 *   <li>If <code>CLAUDE_API_KEY</code> is present but a chat/intelligence provider is
 *       set to <code>stub</code> — that's almost always a misconfiguration. Log ERROR
 *       (always), and refuse to start when the active profile is <code>prod</code>.</li>
 *   <li>If the active profile is <code>prod</code> and any provider is <code>stub</code>,
 *       refuse to start regardless. Stubs in prod are never intentional.</li>
 * </ul>
 */
@Slf4j
@Component
public class ProviderConfigValidator {

    @Value("${app.ai.support.provider:stub}")
    private String supportProvider;

    @Value("${app.intelligence.nlp-provider:stub}")
    private String intelligenceProvider;

    @Value("${app.telemedicine.provider:stub}")
    private String telemedicineProvider;

    @Value("${app.intelligence.claude-api.key:}")
    private String claudeKey;

    @Value("${app.ai.gemini.api-key:}")
    private String geminiKey;

    private final Environment env;

    public ProviderConfigValidator(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        boolean isProd = isProdProfile();
        boolean hasKey = claudeKey != null && !claudeKey.isBlank();

        List<String> hardErrors = new java.util.ArrayList<>();

        if (hasKey && "stub".equalsIgnoreCase(supportProvider)) {
            log.error("[provider-config] CLAUDE_API_KEY is set but AI_SUPPORT_PROVIDER=stub. "
                    + "The in-app help chatbot will return canned responses. "
                    + "Set AI_SUPPORT_PROVIDER=claude-api to use the real model.");
            if (isProd) hardErrors.add("AI_SUPPORT_PROVIDER=stub while CLAUDE_API_KEY is set");
        }

        if (hasKey && "stub".equalsIgnoreCase(intelligenceProvider)) {
            log.error("[provider-config] CLAUDE_API_KEY is set but INTELLIGENCE_NLP_PROVIDER=stub. "
                    + "Doctor-side AI features (chat summary, drafts, Visit Co-Pilot brief) will return canned fallbacks. "
                    + "Set INTELLIGENCE_NLP_PROVIDER=claude-api.");
            if (isProd) hardErrors.add("INTELLIGENCE_NLP_PROVIDER=stub while CLAUDE_API_KEY is set");
        }

        if (isProd && "stub".equalsIgnoreCase(telemedicineProvider)) {
            hardErrors.add("TELEMEDICINE_PROVIDER=stub in prod — stub never issues real video tokens");
        }

        if (isProd && "stub".equalsIgnoreCase(supportProvider)) {
            hardErrors.add("AI_SUPPORT_PROVIDER=stub in prod");
        }
        // Provider-specific key checks: silent fallbacks in prod are worse than
        // a loud refuse-to-start. Catch missing keys for the active provider.
        if (isProd && "gemini".equalsIgnoreCase(supportProvider)
                && (geminiKey == null || geminiKey.isBlank())) {
            hardErrors.add("AI_SUPPORT_PROVIDER=gemini in prod but GEMINI_API_KEY is empty");
        }
        if (isProd && "claude-api".equalsIgnoreCase(supportProvider)
                && (claudeKey == null || claudeKey.isBlank())) {
            hardErrors.add("AI_SUPPORT_PROVIDER=claude-api in prod but CLAUDE_API_KEY is empty");
        }
        if (isProd && "stub".equalsIgnoreCase(intelligenceProvider)) {
            hardErrors.add("INTELLIGENCE_NLP_PROVIDER=stub in prod");
        }

        if (!hardErrors.isEmpty()) {
            String msg = "Refusing to start with stub providers in prod:\n  - "
                    + String.join("\n  - ", hardErrors);
            log.error("[provider-config] {}", msg);
            throw new IllegalStateException(msg);
        }

        log.info("[provider-config] OK — support={}, intelligence={}, telemedicine={}, claudeKeyPresent={}, geminiKeyPresent={}",
                supportProvider, intelligenceProvider, telemedicineProvider, hasKey,
                geminiKey != null && !geminiKey.isBlank());
    }

    private boolean isProdProfile() {
        String[] active = env.getActiveProfiles();
        return Arrays.stream(active).anyMatch(p -> p.equalsIgnoreCase("prod"));
    }
}
