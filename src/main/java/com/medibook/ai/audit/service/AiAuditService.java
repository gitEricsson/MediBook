package com.medibook.ai.audit.service;

import com.medibook.ai.audit.entity.AiMessageAudit;
import com.medibook.ai.audit.repository.AiMessageAuditRepository;
import com.medibook.ai.client.AiChatResponse;
import com.medibook.ai.prompts.PromptTemplateService;
import com.medibook.ai.safety.SafetyClassification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Records every AI call in the immutable ai_message_audit table.
 * Runs in its own transaction to ensure audit is saved even if the main
 * transaction rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAuditService {

    private final AiMessageAuditRepository auditRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiMessageAudit record(
            Long actorId,
            String actorRole,
            String operation,
            Long conversationId,
            Long appointmentId,
            SafetyClassification safety,
            AiChatResponse aiResponse,
            boolean escalationTriggered,
            long latencyMs) {

        AiMessageAudit audit = AiMessageAudit.builder()
                .eventId(UUID.randomUUID().toString())
                .actorId(actorId)
                .actorRole(actorRole)
                .operation(operation)
                .conversationId(conversationId)
                .appointmentId(appointmentId)
                .modelUsed(aiResponse != null ? aiResponse.getModel() : "none")
                .promptVersion(PromptTemplateService.PROMPT_VERSION)
                .safetyLabel(safety.getLabel())
                .inputTokenCount(aiResponse != null ? aiResponse.getInputTokens() : 0)
                .outputTokenCount(aiResponse != null ? aiResponse.getOutputTokens() : 0)
                .escalationTriggered(escalationTriggered)
                .latencyMs(latencyMs)
                .build();

        AiMessageAudit saved = auditRepository.save(audit);
        log.info("AI audit recorded [{}] op={} safety={} escalated={}",
                saved.getEventId(), operation, safety.getLabel(), escalationTriggered);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AiMessageAudit> getConversationAudit(Long conversationId) {
        return auditRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
    }
}
