package com.medibook.ai.orchestration;

import com.medibook.ai.audit.service.AiAuditService;
import com.medibook.ai.client.AiChatClient;
import com.medibook.ai.client.AiChatResponse;
import com.medibook.ai.prompts.PromptTemplateService;
import com.medibook.ai.safety.SafetyClassification;
import com.medibook.ai.safety.SafetyClassifier;
import com.medibook.ai.safety.SafetyLabel;
import com.medibook.chat.entity.AiDraftResponse;
import com.medibook.chat.entity.ChatConversation;
import com.medibook.chat.repository.AiDraftResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Central AI orchestration layer.
 *
 * Routes inbound chat messages to the appropriate sub-operation based on:
 *  - Safety classification result
 *  - Sender role (PATIENT / DOCTOR)
 *  - Conversation state (intake_completed, ai_enabled)
 *
 * NEVER calls the AI client directly from a controller.
 * NEVER sends AI response to patient if safety label is CLINICAL_QUERY or above.
 * ALWAYS records every AI call in ai_message_audit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiOrchestrationService {

    private final SafetyClassifier       safetyClassifier;
    private final AiChatClient           aiChatClient;
    private final PromptTemplateService  promptTemplates;
    private final AiAuditService         auditService;
    private final AiDraftResponseRepository draftRepository;

    @Value("${app.chat.baa-active:false}")
    private boolean baaActive;

    private static final int DEFAULT_MAX_TOKENS = 512;
    private static final int SUMMARY_MAX_TOKENS = 1024;

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Process an inbound patient message:
     *  1. Classify safety
     *  2. If URGENT → return urgency escalation text (caller handles notification)
     *  3. If CLINICAL_QUERY → return null (caller should notify doctor; no AI response)
     *  4. If BLOCKED → return null silently
     *  5. If SAFE → generate assistant response or continue intake
     */
    @Transactional
    public AiOrchestrationResult processPatientMessage(
            ChatConversation conversation,
            String messageBody,
            Long actorId) {

        if (!baaActive) {
            log.warn("AI chat disabled — BAA not active. Set app.chat.baa-active=true after BAA execution.");
            return AiOrchestrationResult.disabled();
        }

        long start = System.currentTimeMillis();

        // 1. Safety classification
        SafetyClassification safety = safetyClassifier.classify(messageBody);
        log.debug("Safety classification for conversation {}: {}", conversation.getId(), safety.getLabel());

        // 2. Handle BLOCKED silently
        if (safety.getLabel() == SafetyLabel.BLOCKED) {
            auditService.record(actorId, "PATIENT", "ASSISTANT", conversation.getId(),
                    conversation.getAppointmentId(), safety, null, false,
                    System.currentTimeMillis() - start);
            return AiOrchestrationResult.blocked();
        }

        // 3. Handle URGENT — return escalation message, do NOT call AI
        if (safety.getLabel() == SafetyLabel.URGENT) {
            String escalationMsg = promptTemplates.urgencyEscalationMessage(safety.getMatchedPatterns());
            auditService.record(actorId, "PATIENT", "URGENCY_CHECK", conversation.getId(),
                    conversation.getAppointmentId(), safety, null, true,
                    System.currentTimeMillis() - start);
            return AiOrchestrationResult.urgent(escalationMsg, safety.getMatchedPatterns());
        }

        // 4. Handle CLINICAL_QUERY — no AI response; flag for doctor
        if (safety.getLabel() == SafetyLabel.CLINICAL_QUERY) {
            auditService.record(actorId, "PATIENT", "ASSISTANT", conversation.getId(),
                    conversation.getAppointmentId(), safety, null, false,
                    System.currentTimeMillis() - start);
            return AiOrchestrationResult.clinicalDeferred(
                    "This question has been flagged for your doctor to answer. They will respond shortly.");
        }

        // 5. SAFE — generate response
        String operation = conversation.isIntakeCompleted() ? "ASSISTANT" : "INTAKE";
        String systemPrompt = conversation.isIntakeCompleted()
                ? promptTemplates.assistantSystemPrompt("your doctor", "appointment")
                : promptTemplates.intakeSystemPrompt("your doctor", "General Medicine");

        AiChatResponse aiResponse = aiChatClient.complete(systemPrompt, messageBody, DEFAULT_MAX_TOKENS);

        auditService.record(actorId, "PATIENT", operation, conversation.getId(),
                conversation.getAppointmentId(), safety, aiResponse, false,
                System.currentTimeMillis() - start);

        return AiOrchestrationResult.success(aiResponse.getText());
    }

    /**
     * Generate a conversation summary for the doctor.
     * NEVER exposes summary to the patient.
     */
    @Transactional
    public String generateSummary(ChatConversation conversation, String conversationText, Long doctorId) {
        long start = System.currentTimeMillis();
        SafetyClassification safety = SafetyClassification.safe(); // summaries are internal only

        AiChatResponse response = aiChatClient.complete(
                promptTemplates.summarySystemPrompt(),
                conversationText,
                SUMMARY_MAX_TOKENS);

        auditService.record(doctorId, "DOCTOR", "SUMMARY", conversation.getId(),
                conversation.getAppointmentId(), safety, response, false,
                System.currentTimeMillis() - start);

        return response.getText();
    }

    /**
     * Generate an AI draft for doctor review.
     * Draft is saved as PENDING and MUST be approved by doctor before sending.
     */
    @Transactional
    public AiDraftResponse generateDraft(
            ChatConversation conversation,
            String patientMessage,
            String doctorName,
            String specialization,
            Long doctorId) {

        long start = System.currentTimeMillis();

        // Even drafts are safety-classified
        SafetyClassification safety = safetyClassifier.classify(patientMessage);
        if (safety.getLabel() == SafetyLabel.BLOCKED) {
            return null;
        }

        AiChatResponse aiResponse = aiChatClient.complete(
                promptTemplates.draftSystemPrompt(doctorName, specialization),
                patientMessage,
                DEFAULT_MAX_TOKENS);

        AiDraftResponse draft = AiDraftResponse.builder()
                .conversationId(conversation.getId())
                .appointmentId(conversation.getAppointmentId())
                .doctorId(doctorId)
                .draftBody(aiResponse.getText())
                .status(AiDraftResponse.DraftStatus.PENDING)
                .promptVersion(PromptTemplateService.PROMPT_VERSION)
                .modelUsed(aiChatClient.modelId())
                .build();

        AiDraftResponse saved = draftRepository.save(draft);

        auditService.record(doctorId, "DOCTOR", "DRAFT", conversation.getId(),
                conversation.getAppointmentId(), safety, aiResponse, false,
                System.currentTimeMillis() - start);

        return saved;
    }

    /**
     * Run intake questions flow — generates next intake question based on
     * conversation history.
     */
    @Transactional
    public AiOrchestrationResult runIntake(
            ChatConversation conversation,
            String conversationHistory,
            Long actorId) {

        long start = System.currentTimeMillis();
        SafetyClassification safety = safetyClassifier.classify(conversationHistory);

        if (safety.getLabel() == SafetyLabel.URGENT) {
            String msg = promptTemplates.urgencyEscalationMessage(safety.getMatchedPatterns());
            auditService.record(actorId, "PATIENT", "INTAKE", conversation.getId(),
                    conversation.getAppointmentId(), safety, null, true,
                    System.currentTimeMillis() - start);
            return AiOrchestrationResult.urgent(msg, safety.getMatchedPatterns());
        }

        AiChatResponse response = aiChatClient.complete(
                promptTemplates.intakeSystemPrompt("your doctor", "General"),
                conversationHistory,
                DEFAULT_MAX_TOKENS);

        auditService.record(actorId, "PATIENT", "INTAKE", conversation.getId(),
                conversation.getAppointmentId(), safety, response, false,
                System.currentTimeMillis() - start);

        return AiOrchestrationResult.success(response.getText());
    }
}
