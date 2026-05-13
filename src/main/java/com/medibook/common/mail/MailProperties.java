package com.medibook.common.mail;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    private String fromAddress;
    private String fromName = "MediBook";
    private boolean failoverEnabled = true;
    private final Brevo brevo = new Brevo();

    @Data
    public static class Brevo {
        private boolean enabled = false;
        private String apiKey;
        private String baseUrl = "https://api.brevo.com";
    }
}
