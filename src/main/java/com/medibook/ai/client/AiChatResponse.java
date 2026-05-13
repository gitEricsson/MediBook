package com.medibook.ai.client;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AiChatResponse {
    String text;
    String model;
    int inputTokens;
    int outputTokens;
    boolean fallback;   // true if returned from fallback (error path)

    public static AiChatResponse fallback(String model) {
        return AiChatResponse.builder()
                .text("I'm unable to assist at the moment. Please contact your care team directly or call the clinic.")
                .model(model)
                .inputTokens(0)
                .outputTokens(0)
                .fallback(true)
                .build();
    }
}
