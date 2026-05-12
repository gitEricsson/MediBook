package com.medibook.ai.support.service;

import com.medibook.ai.support.classifier.SupportMessageClassifier;
import com.medibook.ai.support.dto.SupportChatRequest;
import com.medibook.ai.support.dto.SupportChatResponse;
import com.medibook.ai.support.dto.SupportMessageClassification;
import com.medibook.ai.support.prompt.SupportPromptBuilder;
import com.medibook.ai.support.provider.SupportAiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the support chat pipeline:
 *
 *  1. Resolve session ID
 *  2. Classify message (safety + intent)
 *  3. Short-circuit for blocked/emergency/symptom/PHI
 *  4. Build prompt and call SupportAiProvider
 *  5. Return typed response
 *  6. Log safely (no PHI in logs)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSupportService {

    private final SupportMessageClassifier classifier;
    private final SupportAiProvider        provider;
    private final SupportPromptBuilder     promptBuilder;

    public SupportChatResponse chat(SupportChatRequest request, Authentication authentication) {
        long start = System.currentTimeMillis();

        String sessionId = resolveSessionId(request.sessionId());
        String userId    = resolveUserId(authentication);
        String userRole  = resolveRole(authentication);

        SupportMessageClassifier.ClassificationResult classified = classifier.classify(request.message());
        SupportMessageClassification cls = classified.classification();

        log.info("SupportChat session={} userId={} role={} classification={} provider={} patterns={}",
                sessionId, userId, userRole, cls, provider.providerId(),
                classified.matchedPatterns());

        SupportChatResponse response = switch (cls) {
            case MEDICAL_EMERGENCY -> {
                log.warn("SupportChat MEDICAL_EMERGENCY — session={} userId={}", sessionId, userId);
                yield SupportChatResponse.emergencyEscalation(sessionId);
            }
            case MEDICAL_SYMPTOM -> {
                log.info("SupportChat MEDICAL_SYMPTOM — redirecting to doctor booking session={}", sessionId);
                yield SupportChatResponse.medicalRefusal(sessionId);
            }
            case PHI_DETECTED -> {
                log.warn("SupportChat PHI_DETECTED — refusing to forward session={}", sessionId);
                yield SupportChatResponse.safeRefusal(cls, sessionId);
            }
            case ABUSE_OR_SPAM -> {
                log.warn("SupportChat ABUSE_OR_SPAM blocked — session={}", sessionId);
                yield SupportChatResponse.safeRefusal(cls, sessionId);
            }
            default -> generateAiResponse(request.message(), cls, sessionId, start);
        };

        log.info("SupportChat complete session={} classification={} requiresHuman={} latencyMs={}",
                sessionId, cls, response.isRequiresHumanSupport(), System.currentTimeMillis() - start);

        return response;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SupportChatResponse generateAiResponse(
            String message,
            SupportMessageClassification cls,
            String sessionId,
            long start) {

        try {
            String systemPrompt = cls == SupportMessageClassification.GENERAL_HEALTH_EDUCATION
                    ? promptBuilder.buildHealthEducationPrompt()
                    : promptBuilder.buildSystemPrompt();

            String reply = provider.generate(systemPrompt, message);

            boolean requiresHumanSupport = cls == SupportMessageClassification.UNKNOWN
                    || reply.isBlank();

            return SupportChatResponse.builder()
                    .reply(reply.isBlank()
                            ? "I'm not sure I can help with that. Please contact MediBook support."
                            : reply)
                    .classification(cls)
                    .requiresHumanSupport(requiresHumanSupport)
                    .sessionId(sessionId)
                    .build();

        } catch (Exception ex) {
            log.error("AiSupportService provider error session={} latencyMs={}: {}",
                    sessionId, System.currentTimeMillis() - start, ex.getMessage());
            return SupportChatResponse.serviceUnavailable(sessionId);
        }
    }

    private String resolveSessionId(String provided) {
        if (provided != null && !provided.isBlank()) return provided;
        return UUID.randomUUID().toString();
    }

    private String resolveUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return "anonymous";
        return auth.getName();
    }

    private String resolveRole(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return "ANONYMOUS";
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
    }
}
