package com.medibook.domain.telemedicine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.telemedicine.twilio")
public class TwilioProperties {

    private String accountSid;
    private String apiKeySid;
    private String apiKeySecret;
    private String videoRoomType = "group";
    private long videoTokenTtlSeconds = 900;
    private String videoStatusCallbackUrl;
    /** Hard ceiling on how long a Twilio room stays open (seconds). Default: 3600 (1 hour). */
    private long roomMaxDurationSeconds = 3600;

    public boolean isVideoConfigured() {
        return hasText(accountSid) && hasText(apiKeySid) && hasText(apiKeySecret);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
