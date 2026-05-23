package com.medibook.domain.telemedicine.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.telemedicine.config.TwilioProperties;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VideoGrant;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class TwilioTokenService {

    private final TwilioProperties properties;

    /**
     * Validate and log (masked) Twilio credentials at startup.
     * Logs an ERROR for obviously wrong formats so misconfiguration is
     * caught immediately in the pod logs, not at the first call attempt.
     *
     * Expected formats:
     *   accountSid  → starts with "AC", 34 chars total
     *   apiKeySid   → starts with "SK", 34 chars total
     *   apiKeySecret → any non-blank string (Twilio generates 32 chars)
     */
    @PostConstruct
    public void validateCredentials() {
        if (!properties.isVideoConfigured()) {
            log.warn("[Twilio] Video credentials not fully configured — telemedicine calls will return 503");
            return;
        }

        String acct = properties.getAccountSid();
        String key  = properties.getApiKeySid();

        boolean acctOk = acct.startsWith("AC") && acct.length() == 34;
        boolean keyOk  = key.startsWith("SK")  && key.length()  == 34;

        if (!acctOk) {
            log.error("[Twilio] accountSid format invalid: prefix='{}' length={} — must start with 'AC' and be 34 chars",
                    acct.substring(0, Math.min(4, acct.length())), acct.length());
        }
        if (!keyOk) {
            log.error("[Twilio] apiKeySid format invalid: prefix='{}' length={} — must start with 'SK' and be 34 chars",
                    key.substring(0, Math.min(4, key.length())), key.length());
        }

        // Log masked values so operators can cross-check against the Twilio console
        // without exposing the full secret in logs.
        log.info("[Twilio] Video configured OK — accountSid={}...{} apiKeySid={}...{} ttl={}s",
                acct.substring(0, 4),
                acct.substring(acct.length() - 4),
                key.substring(0, 4),
                key.substring(key.length() - 4),
                properties.getVideoTokenTtlSeconds());
    }

    public TokenResult generateVideoToken(String identity, String roomName) {
        if (!properties.isVideoConfigured()) {
            throw new MediBookException(
                    "Telemedicine is not configured on this environment. Please contact support.",
                    HttpStatus.SERVICE_UNAVAILABLE, "TELEMEDICINE_NOT_CONFIGURED");
        }

        try {
            VideoGrant grant = new VideoGrant();
            grant.setRoom(roomName);

            AccessToken token = new AccessToken.Builder(
                    properties.getAccountSid(),
                    properties.getApiKeySid(),
                    properties.getApiKeySecret().getBytes(StandardCharsets.UTF_8))
                    .identity(identity)
                    .grant(grant)
                    .ttl((int) properties.getVideoTokenTtlSeconds())
                    .build();

            Instant expiresAt = Instant.now().plusSeconds(properties.getVideoTokenTtlSeconds());
            return new TokenResult(token.toJwt(), expiresAt);
        } catch (RuntimeException ex) {
            throw new MediBookException("Could not create Twilio video token",
                    HttpStatus.BAD_GATEWAY, "TWILIO_TOKEN_CREATE_FAILED");
        }
    }

    public record TokenResult(String token, Instant expiresAt) {}
}
