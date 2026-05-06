package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Manages opaque refresh tokens stored in Redis.
 * On rotation: old token is revoked and a new one issued atomically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String PREFIX = "refresh:";
    private static final String REVOKED_PREFIX = "revoked:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtTokenProvider tokenProvider;

    public String createRefreshToken(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                PREFIX + token,
                String.valueOf(userId),
                Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs()));
        return token;
    }

    public Long validateAndGetUserId(String token) {
        if (isRevoked(token)) {
            throw new MediBookException("Refresh token has been revoked", HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED");
        }
        Object userId = redisTemplate.opsForValue().get(PREFIX + token);
        if (userId == null) {
            throw new MediBookException("Refresh token expired or invalid", HttpStatus.UNAUTHORIZED, "TOKEN_INVALID");
        }
        return Long.parseLong(userId.toString());
    }

    /** Rotate: atomically revoke old token and issue new one via Redis pipeline */
    @SuppressWarnings("unchecked")
    public String rotate(String oldToken) {
        Long userId = validateAndGetUserId(oldToken);
        String newToken = UUID.randomUUID().toString();
        Duration expiration = Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs());

        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.delete(PREFIX + oldToken);
                operations.opsForValue().set(REVOKED_PREFIX + oldToken, "1", expiration);
                operations.opsForValue().set(PREFIX + newToken, String.valueOf(userId), expiration);
                return null;
            }
        });
        return newToken;
    }

    public void revoke(String token) {
        redisTemplate.delete(PREFIX + token);
        // Keep revocation record for 7 days to detect re-use
        redisTemplate.opsForValue().set(
                REVOKED_PREFIX + token, "1",
                Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs()));
    }

    private boolean isRevoked(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_PREFIX + token));
    }
}
