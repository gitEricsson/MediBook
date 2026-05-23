package com.medibook.domain.telemedicine.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.telemedicine.config.TwilioProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TwilioTokenService {

    private final TwilioProperties properties;

    public TokenResult generateVideoToken(String identity, String roomName) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.getVideoTokenTtlSeconds());

        if (!properties.isVideoConfigured()) {
            // connect() time. Surface a clear 503 so the UI can show "telemedicine not
            // configured" instead of a broken room.
            throw new MediBookException(
                    "Telemedicine is not configured on this environment. Please contact support.",
                    HttpStatus.SERVICE_UNAVAILABLE, "TELEMEDICINE_NOT_CONFIGURED");
        }

        try {
            SecretKey key = Keys.hmacShaKeyFor(properties.getApiKeySecret().getBytes(StandardCharsets.UTF_8));
            // jti must be globally unique per token issuance to prevent replay.
            // Using apiKeySid + nanosecond epoch + random suffix gives sufficient entropy.
            String jti = properties.getApiKeySid() + "-" + now.getEpochSecond()
                    + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String token = Jwts.builder()
                    .header()
                    .add("cty", "twilio-fpa;v=1")
                    .and()
                    .id(jti)
                    .issuer(properties.getApiKeySid())
                    .subject(properties.getAccountSid())
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(expiresAt))
                    .claim("grants", Map.of(
                            "identity", identity,
                            "video", Map.of("room", roomName)
                    ))
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();
            return new TokenResult(token, expiresAt);
        } catch (RuntimeException ex) {
            throw new MediBookException("Could not create Twilio video token",
                    HttpStatus.BAD_GATEWAY, "TWILIO_TOKEN_CREATE_FAILED");
        }
    }

    public record TokenResult(String token, Instant expiresAt) {}
}
