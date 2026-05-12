package com.medibook.ai.support.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SupportChatResponse {

    String reply;
    SupportMessageClassification classification;
    boolean requiresHumanSupport;

    /** Echo of the session ID so the client can maintain conversation state */
    String sessionId;

    public static SupportChatResponse emergencyEscalation(String sessionId) {
        return SupportChatResponse.builder()
                .reply("This sounds like it may be a medical emergency. Please call your local emergency services " +
                       "(911 / 999 / 112) or go to the nearest emergency room immediately. " +
                       "MediBook Assistant cannot provide emergency diagnosis or treatment. " +
                       "If it is safe to do so, you can also book an urgent appointment with a doctor on MediBook.")
                .classification(SupportMessageClassification.MEDICAL_EMERGENCY)
                .requiresHumanSupport(true)
                .sessionId(sessionId)
                .build();
    }

    public static SupportChatResponse medicalRefusal(String sessionId) {
        return SupportChatResponse.builder()
                .reply("I'm not able to diagnose symptoms or recommend treatments — that requires a qualified doctor. " +
                       "I'd recommend booking an appointment with a doctor on MediBook who can assess you properly. " +
                       "If your symptoms are severe or worsening, please seek urgent care.")
                .classification(SupportMessageClassification.MEDICAL_SYMPTOM)
                .requiresHumanSupport(true)
                .sessionId(sessionId)
                .build();
    }

    public static SupportChatResponse safeRefusal(SupportMessageClassification classification, String sessionId) {
        return SupportChatResponse.builder()
                .reply("I'm sorry, I can't help with that request. For further assistance, " +
                       "please contact MediBook support.")
                .classification(classification)
                .requiresHumanSupport(true)
                .sessionId(sessionId)
                .build();
    }

    public static SupportChatResponse serviceUnavailable(String sessionId) {
        return SupportChatResponse.builder()
                .reply("The assistant is temporarily unavailable. Please try again in a moment, " +
                       "or contact MediBook support directly.")
                .classification(SupportMessageClassification.UNKNOWN)
                .requiresHumanSupport(true)
                .sessionId(sessionId)
                .build();
    }
}
