package com.medibook.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.ai.orchestration.AiOrchestrationResult;
import com.medibook.ai.orchestration.AiOrchestrationService;
import com.medibook.chat.dto.*;
import com.medibook.chat.entity.*;
import com.medibook.chat.repository.*;
import com.medibook.chat.twilio.TwilioConversationsService;
import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.messaging.event.ChatEvent;
import com.medibook.messaging.producer.ChatEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Chat domain service.
 *
 * Orchestrates:
 *  1. Conversation lifecycle (create, close)
 *  2. Twilio webhook event handling
 *  3. AI pipeline delegation to AiOrchestrationService
 *  4. Urgency escalation
 *  5. Doctor draft approval flow
 *  6. Patient consent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatConversationRepository conversationRepo;
    private final ChatMessageRepository      messageRepo;
    private final AiDraftResponseRepository  draftRepo;
    private final UrgencyAlertRepository     urgencyRepo;
    private final AiConsentRecordRepository  consentRepo;
    private final AppointmentRepository      appointmentRepo;

    private final TwilioConversationsService twilioService;
    private final AiOrchestrationService     aiOrchestration;
    private final ChatEventProducer          eventProducer;
    private final NotificationService        notificationService;
    private final ObjectMapper               objectMapper;

    // ── Conversation Lifecycle ────────────────────────────────────────────────

    @Transactional
    public ConversationResponse createConversation(Long appointmentId, Long requesterId) {
        Appointment appt = appointmentRepo.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", appointmentId));

        // Verify the requester is the patient or doctor for this appointment
        boolean isPatient = appt.getPatient().getId().equals(requesterId);
        boolean isDoctor  = appt.getDoctor().getUser().getId().equals(requesterId);
        if (!isPatient && !isDoctor) {
            throw new MediBookException("Not authorized to create conversation for this appointment",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        // Idempotent — return existing if already created
        return conversationRepo.findByAppointmentId(appointmentId)
                .map(this::toConversationResponse)
                .orElseGet(() -> {
                    String sid = twilioService.createConversation("Appt #" + appointmentId);
                    ChatConversation conv = ChatConversation.builder()
                            .twilioConversationSid(sid)
                            .appointmentId(appointmentId)
                            .patientId(appt.getPatient().getId())
                            .doctorId(appt.getDoctor().getUser().getId())
                            .build();
                    ChatConversation saved = conversationRepo.save(conv);
                    log.info("Created conversation {} for appointment {}", saved.getId(), appointmentId);
                    afterCommit(() -> eventProducer.publishChatEvent(
                            buildEvent("CHAT_CONVERSATION_CREATED", saved, null, null)));
                    return toConversationResponse(saved);
                });
    }

    @Transactional(readOnly = true)
    public ConversationResponse getConversation(Long conversationId, Long requesterId) {
        return toConversationResponse(loadAndAuthorize(conversationId, requesterId));
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getMessages(Long conversationId, Long requesterId) {
        ChatConversation conv = loadAndAuthorize(conversationId, requesterId);
        return messageRepo.findByConversationIdOrderByCreatedAtAsc(conv.getId())
                .stream()
                .map(this::toMessageResponse)
                .toList();
    }

    // ── Twilio Webhook Handler ────────────────────────────────────────────────

    /**
     * Handle an 'onMessageAdded' event from Twilio Conversations.
     * Routes the message through safety classification and AI orchestration.
     */
    @Transactional
    public void handleTwilioWebhook(Map<String, String> params) {
        String eventType = params.get("EventType");
        if (!"onMessageAdded".equals(eventType)) {
            log.debug("Ignoring Twilio event type: {}", eventType);
            return;
        }

        String conversationSid = params.get("ConversationSid");
        String author          = params.get("Author");
        String body            = params.get("Body");
        String messageSid      = params.get("MessageSid");

        // Ignore AI's own messages to avoid infinite loops
        if (TwilioConversationsService.AI_IDENTITY.equals(author)) {
            log.debug("Ignoring AI's own message from {}", author);
            return;
        }

        ChatConversation conv = conversationRepo.findByTwilioConversationSid(conversationSid)
                .orElse(null);
        if (conv == null) {
            log.warn("Webhook for unknown conversation SID: {}", conversationSid);
            return;
        }

        // Determine sender role and ID
        boolean senderIsPatient = author != null && String.valueOf(conv.getPatientId()).equals(author);
        ChatMessage.SenderRole senderRole = senderIsPatient
                ? ChatMessage.SenderRole.PATIENT
                : ChatMessage.SenderRole.DOCTOR;
        Long senderId = senderIsPatient ? conv.getPatientId() : conv.getDoctorId();

        // Persist the inbound message
        ChatMessage msg = ChatMessage.builder()
                .conversationId(conv.getId())
                .twilioMessageSid(messageSid != null ? messageSid : "webhook_" + System.currentTimeMillis())
                .senderId(senderId)
                .senderRole(senderRole)
                .body(body)
                .aiGenerated(false)
                .build();
        messageRepo.save(msg);

        afterCommit(() -> eventProducer.publishChatEvent(
                buildEvent("CHAT_MESSAGE_RECEIVED", conv, null, null)));

        // Only run AI if AI is enabled and patient consented
        if (!conv.isAiEnabled() || !hasActiveConsent(conv.getPatientId(), conv.getId())) {
            return;
        }

        // Delegate to AI orchestration (patient messages only for now)
        if (senderRole == ChatMessage.SenderRole.PATIENT) {
            AiOrchestrationResult result = aiOrchestration.processPatientMessage(conv, body, senderId);
            handleOrchestrationResult(conv, msg, result);
        }
    }

    private void handleOrchestrationResult(ChatConversation conv, ChatMessage inbound,
                                           AiOrchestrationResult result) {
        if (!result.shouldPostToConversation()) return;

        if (result.isUrgent()) {
            handleUrgency(conv, inbound, result);
        } else {
            // Post AI message to Twilio
            String aiSid = twilioService.postAiMessage(conv.getTwilioConversationSid(), result.getMessage());
            persistAiMessage(conv.getId(), aiSid, result.getMessage());
            afterCommit(() -> eventProducer.publishChatEvent(
                    buildEvent("AI_RESPONSE_CREATED", conv, null, null)));
        }
    }

    private void handleUrgency(ChatConversation conv, ChatMessage inbound, AiOrchestrationResult result) {
        // Post escalation message to patient
        String aiSid = twilioService.postAiMessage(conv.getTwilioConversationSid(), result.getMessage());
        persistAiMessage(conv.getId(), aiSid, result.getMessage());

        // Persist urgency alert
        try {
            String keywordsJson = objectMapper.writeValueAsString(result.getUrgencyFlags());
            UrgencyAlert alert = UrgencyAlert.builder()
                    .conversationId(conv.getId())
                    .messageId(inbound.getId())
                    .patientId(conv.getPatientId())
                    .doctorId(conv.getDoctorId())
                    .urgencyKeywords(keywordsJson)
                    .alertMessage(result.getMessage())
                    .escalated(true)
                    .build();
            urgencyRepo.save(alert);
        } catch (Exception ex) {
            log.error("Failed to persist urgency alert: {}", ex.getMessage());
        }

        // Notify doctor via existing notification pipeline + Kafka
        afterCommit(() -> {
            notificationService.sendUrgencyAlert(conv.getDoctorId(), conv.getId(),
                    String.join(", ", result.getUrgencyFlags()));
            eventProducer.publishChatEvent(ChatEvent.builder()
                    .conversationId(conv.getId())
                    .appointmentId(conv.getAppointmentId())
                    .patientId(conv.getPatientId())
                    .doctorId(conv.getDoctorId())
                    .eventType("MEDICAL_URGENCY_FLAGGED")
                    .urgencyKeywords(String.join(", ", result.getUrgencyFlags()))
                    .safetyLabel("URGENT")
                    .build());
        });
    }

    // ── AI Operations ─────────────────────────────────────────────────────────

    @Transactional
    public AiSummaryResponse generateSummary(Long conversationId, Long doctorId) {
        ChatConversation conv = loadAndAuthorize(conversationId, doctorId);
        requireDoctorOwnership(conv, doctorId);

        String context = buildConversationContext(conv.getId());
        String summary = aiOrchestration.generateSummary(conv, context, doctorId);

        afterCommit(() -> eventProducer.publishChatEvent(
                buildEvent("AI_SUMMARY_CREATED", conv, null, null)));

        return new AiSummaryResponse(conversationId, summary, LocalDateTime.now());
    }

    @Transactional
    public AiDraftResponse generateDraft(Long conversationId, String patientMessage, Long doctorId) {
        ChatConversation conv = loadAndAuthorize(conversationId, doctorId);
        requireDoctorOwnership(conv, doctorId);

        // Fetch doctor info for prompt context
        AiDraftResponse draft = aiOrchestration.generateDraft(
                conv, patientMessage, "Doctor", "General Medicine", doctorId);

        if (draft == null) {
            throw new MediBookException("Draft could not be generated — content was blocked",
                    HttpStatus.UNPROCESSABLE_ENTITY, "DRAFT_BLOCKED");
        }

        afterCommit(() -> eventProducer.publishChatEvent(
                buildEvent("AI_DRAFT_CREATED", conv, draft.getId(), null)));

        return draft;
    }

    @Transactional
    public AiDraftResponse approveDraft(Long conversationId, Long draftId, String editedBody, Long doctorId) {
        ChatConversation conv = loadAndAuthorize(conversationId, doctorId);
        requireDoctorOwnership(conv, doctorId);

        AiDraftResponse draft = draftRepo.findById(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("AiDraftResponse", "id", draftId));

        if (draft.getStatus() != AiDraftResponse.DraftStatus.PENDING) {
            throw new MediBookException("Draft is not in PENDING state",
                    HttpStatus.BAD_REQUEST, "INVALID_DRAFT_STATE");
        }

        if (editedBody != null && !editedBody.isBlank()) {
            draft.setEditedBody(editedBody);
        }

        draft.setStatus(AiDraftResponse.DraftStatus.APPROVED);
        draft.setApprovedBy(doctorId);
        draft.setApprovedAt(LocalDateTime.now());
        AiDraftResponse saved = draftRepo.save(draft);

        // Post the approved message to Twilio as the doctor
        String docIdentity = "doctor_" + doctorId;
        twilioService.postDoctorMessage(conv.getTwilioConversationSid(),
                saved.getEffectiveBody(), docIdentity);
        saved.setStatus(AiDraftResponse.DraftStatus.SENT);
        draftRepo.save(saved);

        return saved;
    }

    @Transactional
    public AiDraftResponse rejectDraft(Long conversationId, Long draftId, Long doctorId) {
        loadAndAuthorize(conversationId, doctorId);
        AiDraftResponse draft = draftRepo.findById(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("AiDraftResponse", "id", draftId));
        draft.setStatus(AiDraftResponse.DraftStatus.REJECTED);
        return draftRepo.save(draft);
    }

    @Transactional
    public AiSummaryResponse runIntake(Long conversationId, Long patientId) {
        ChatConversation conv = loadAndAuthorize(conversationId, patientId);
        if (!conv.isAiEnabled() || !hasActiveConsent(patientId, conversationId)) {
            throw new MediBookException("AI is not enabled or consent not granted",
                    HttpStatus.FORBIDDEN, "AI_NOT_ENABLED");
        }

        String history = buildConversationContext(conv.getId());
        AiOrchestrationResult result = aiOrchestration.runIntake(conv, history, patientId);

        if (result.shouldPostToConversation()) {
            String aiSid = twilioService.postAiMessage(conv.getTwilioConversationSid(), result.getMessage());
            persistAiMessage(conv.getId(), aiSid, result.getMessage());
        }

        return new AiSummaryResponse(conversationId, result.getMessage(), LocalDateTime.now());
    }

    // ── Consent ───────────────────────────────────────────────────────────────

    @Transactional
    public void grantConsent(Long conversationId, Long patientId, boolean granted,
                             String ip, String userAgent) {
        ChatConversation conv = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));

        if (!conv.getPatientId().equals(patientId)) {
            throw new MediBookException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        // Upsert consent record
        AiConsentRecord consent = consentRepo.findByPatientIdAndConversationId(patientId, conversationId)
                .orElse(AiConsentRecord.builder()
                        .patientId(patientId)
                        .conversationId(conversationId)
                        .consentVersion("v1")
                        .consentText("AI participation consent accepted.")
                        .build());

        consent.setConsentGranted(granted);
        consent.setIpAddress(ip);
        consent.setUserAgent(userAgent);
        if (granted) {
            consent.setGrantedAt(LocalDateTime.now());
            consent.setRevokedAt(null);
        } else {
            consent.setRevokedAt(LocalDateTime.now());
        }

        consentRepo.save(consent);

        // Enable/disable AI on the conversation
        conv.setAiEnabled(granted);
        conversationRepo.save(conv);

        String eventType = granted ? "AI_CONSENT_GRANTED" : "AI_CONSENT_REVOKED";
        afterCommit(() -> eventProducer.publishChatEvent(
                buildEvent(eventType, conv, null, null)));

        log.info("AI consent {} for patient {} in conversation {}", granted ? "GRANTED" : "REVOKED",
                patientId, conversationId);
    }

    // ── Urgency Acknowledgement ───────────────────────────────────────────────

    @Transactional
    public void acknowledgeUrgency(Long conversationId, Long alertId, Long doctorId) {
        loadAndAuthorize(conversationId, doctorId);
        UrgencyAlert alert = urgencyRepo.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("UrgencyAlert", "id", alertId));
        alert.setAcknowledgedBy(doctorId);
        alert.setAcknowledgedAt(LocalDateTime.now());
        urgencyRepo.save(alert);
    }

    @Transactional(readOnly = true)
    public List<UrgencyAlert> getUnacknowledgedAlerts(Long doctorId) {
        return urgencyRepo.findByDoctorIdAndAcknowledgedAtIsNullOrderByCreatedAtDesc(doctorId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChatConversation loadAndAuthorize(Long conversationId, Long userId) {
        ChatConversation conv = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));

        boolean hasAccess = conv.getPatientId().equals(userId)
                || conv.getDoctorId().equals(userId);
        if (!hasAccess) {
            throw new MediBookException("Access denied to conversation " + conversationId,
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return conv;
    }

    private void requireDoctorOwnership(ChatConversation conv, Long doctorId) {
        if (!conv.getDoctorId().equals(doctorId)) {
            throw new MediBookException("Only the assigned doctor can perform this action",
                    HttpStatus.FORBIDDEN, "DOCTOR_ONLY");
        }
    }

    private boolean hasActiveConsent(Long patientId, Long conversationId) {
        return consentRepo.findByPatientIdAndConversationId(patientId, conversationId)
                .map(AiConsentRecord::isActive)
                .orElse(false);
    }

    private String buildConversationContext(Long conversationId) {
        List<ChatMessage> messages = messageRepo.findContextMessages(conversationId);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            sb.append("[").append(m.getSenderRole().name()).append("]: ").append(m.getBody()).append("\n");
        }
        return sb.toString();
    }

    private void persistAiMessage(Long conversationId, String twilioSid, String body) {
        ChatMessage aiMsg = ChatMessage.builder()
                .conversationId(conversationId)
                .twilioMessageSid(twilioSid)
                .senderRole(ChatMessage.SenderRole.AI_ASSISTANT)
                .body(body)
                .aiGenerated(true)
                .build();
        messageRepo.save(aiMsg);
    }

    private ConversationResponse toConversationResponse(ChatConversation c) {
        return new ConversationResponse(c.getId(), c.getTwilioConversationSid(),
                c.getAppointmentId(), c.getPatientId(), c.getDoctorId(),
                c.getStatus().name(), c.isAiEnabled(), c.getCreatedAt());
    }

    private MessageResponse toMessageResponse(ChatMessage m) {
        return new MessageResponse(m.getId(), m.getConversationId(), m.getTwilioMessageSid(),
                m.getSenderId(), m.getSenderRole().name(), m.getBody(),
                m.isAiGenerated(), m.getSafetyLabel() != null ? m.getSafetyLabel().name() : null,
                m.getCreatedAt());
    }

    private ChatEvent buildEvent(String type, ChatConversation conv, Long draftId, String keywords) {
        return ChatEvent.builder()
                .eventType(type)
                .conversationId(conv.getId())
                .appointmentId(conv.getAppointmentId())
                .patientId(conv.getPatientId())
                .doctorId(conv.getDoctorId())
                .draftId(draftId)
                .urgencyKeywords(keywords)
                .build();
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }
}
