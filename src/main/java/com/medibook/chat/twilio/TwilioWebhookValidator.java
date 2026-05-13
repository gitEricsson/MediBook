package com.medibook.chat.twilio;

import com.twilio.security.RequestValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * Validates the X-Twilio-Signature header on inbound webhook requests.
 *
 * Without this validation, any party could POST fake messages to our webhook
 * and trigger AI responses or urgency escalations.
 *
 * In stub mode (no auth token), validation is skipped and a warning is logged.
 */
@Slf4j
@Component
public class TwilioWebhookValidator {

    @Value("${twilio.auth-token:#{null}}")
    private String authToken;

    /**
     * Returns true if the request is a valid Twilio webhook.
     * In stub/test mode, always returns true with a warning.
     */
    public boolean isValid(HttpServletRequest request, Map<String, String[]> params) {
        if (authToken == null || authToken.isBlank()) {
            log.warn("Twilio webhook validation SKIPPED — authToken not configured. " +
                     "This is insecure in production.");
            return true;
        }

        try {
            RequestValidator validator = new RequestValidator(authToken);

            String url = request.getRequestURL().toString();

            // Build flat param map (Twilio validator expects single values)
            Map<String, String> flatParams = new TreeMap<>();
            params.forEach((k, v) -> {
                if (v != null && v.length > 0) flatParams.put(k, v[0]);
            });

            String signature = request.getHeader("X-Twilio-Signature");
            if (signature == null || signature.isBlank()) {
                log.warn("Missing X-Twilio-Signature header");
                return false;
            }

            boolean valid = validator.validate(url, flatParams, signature);
            if (!valid) {
                log.warn("Invalid Twilio signature for URL: {}", url);
            }
            return valid;

        } catch (Exception ex) {
            log.error("Twilio webhook validation error: {}", ex.getMessage());
            return false;
        }
    }
}
