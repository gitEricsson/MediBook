package com.medibook.domain.telemedicine.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.telemedicine.config.TwilioProperties;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VideoGrant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class TwilioTokenService {

    private final TwilioProperties properties;

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
