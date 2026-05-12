package com.medibook.ai.orchestration;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Result returned by AiOrchestrationService.
 * The ChatService maps this to the appropriate action.
 */
@Value
@Builder
public class AiOrchestrationResult {

    public enum ResultType {
        SUCCESS,           // Post AI response to conversation
        URGENT,            // Post escalation message + emit Kafka MEDICAL_URGENCY_FLAGGED
        CLINICAL_DEFERRED, // Send "flagged for doctor" message; no AI response
        BLOCKED,           // Do nothing silently
        DISABLED           // AI not configured / BAA not active
    }

    ResultType type;
    String message;            // Text to post to Twilio (null if BLOCKED/DISABLED)
    List<String> urgencyFlags; // Matched urgency keywords (non-empty when URGENT)

    public boolean shouldPostToConversation() {
        return type == ResultType.SUCCESS
                || type == ResultType.URGENT
                || type == ResultType.CLINICAL_DEFERRED;
    }

    public boolean isUrgent() { return type == ResultType.URGENT; }

    public static AiOrchestrationResult success(String message) {
        return builder().type(ResultType.SUCCESS).message(message).urgencyFlags(List.of()).build();
    }

    public static AiOrchestrationResult urgent(String message, List<String> flags) {
        return builder().type(ResultType.URGENT).message(message).urgencyFlags(flags).build();
    }

    public static AiOrchestrationResult clinicalDeferred(String message) {
        return builder().type(ResultType.CLINICAL_DEFERRED).message(message).urgencyFlags(List.of()).build();
    }

    public static AiOrchestrationResult blocked() {
        return builder().type(ResultType.BLOCKED).urgencyFlags(List.of()).build();
    }

    public static AiOrchestrationResult disabled() {
        return builder().type(ResultType.DISABLED).urgencyFlags(List.of()).build();
    }
}
