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
 *
 * Token layout:
 *   refresh:{token}  → userId  (expires after refresh-token TTL)
 *   revoked:{token}  → "1"     (kept for the same TTL to detect re-use after rotation)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String PREFIX               = "refresh:";
    private static final String REVOKED_PREFIX       = "revoked:";
    private static final String USER_SESSIONS_PREFIX = "user_sessions:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtTokenProvider              tokenProvider;


    @SuppressWarnings("unchecked")
    public String createRefreshToken(Long userId) {
        String   token  = UUID.randomUUID().toString();
        Duration expiry = Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs());

        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.opsForValue().set(PREFIX + token, String.valueOf(userId), expiry);
                operations.opsForSet().add(USER_SESSIONS_PREFIX + userId, token);
                operations.expire(USER_SESSIONS_PREFIX + userId, expiry);
                return null;
            }
        });
        return token;
    }


    public Long validateAndGetUserId(String token) {
        if (isRevoked(token)) {
            throw new MediBookException(
                    "Refresh token has been revoked", HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED");
        }
        Object userId = redisTemplate.opsForValue().get(PREFIX + token);
        if (userId == null) {
            throw new MediBookException(
                    "Refresh token expired or invalid", HttpStatus.UNAUTHORIZED, "TOKEN_INVALID");
        }
        return Long.parseLong(userId.toString());
    }


    /**
     * Atomically revokes {@code oldToken} and issues a new one.
     * Returns both the resolved userId and the new token so callers avoid
     * a redundant {@link #validateAndGetUserId} call.
     */
    @SuppressWarnings("unchecked")
    public RotationResult rotate(String oldToken) {
        Long   userId     = validateAndGetUserId(oldToken);
        String newToken   = UUID.randomUUID().toString();
        Duration expiry   = Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs());

        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.delete(PREFIX + oldToken);
                operations.opsForValue().set(REVOKED_PREFIX + oldToken, "1", expiry);
                operations.opsForValue().set(PREFIX + newToken, String.valueOf(userId), expiry);
                // Maintain per-user session set for admin bulk revocation
                operations.opsForSet().remove(USER_SESSIONS_PREFIX + userId, oldToken);
                operations.opsForSet().add(USER_SESSIONS_PREFIX + userId, newToken);
                operations.expire(USER_SESSIONS_PREFIX + userId, expiry);
                return null;
            }
        });

        return new RotationResult(userId, newToken);
    }


    public void revoke(String token) {
        Long userId = null;
        try {
            Object stored = redisTemplate.opsForValue().get(PREFIX + token);
            if (stored != null) userId = Long.parseLong(stored.toString());
        } catch (Exception ignored) {}

        final Long resolvedUserId = userId;
        Duration expiry = Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs());
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.delete(PREFIX + token);
                operations.opsForValue().set(REVOKED_PREFIX + token, "1", expiry);
                if (resolvedUserId != null) {
                    operations.opsForSet().remove(USER_SESSIONS_PREFIX + resolvedUserId, token);
                }
                return null;
            }
        });
    }

    /**
     * Revokes all active refresh tokens for a user — used by admin force-logout.
     */
    @SuppressWarnings("unchecked")
    public int revokeAllForUser(Long userId) {
        java.util.Set<Object> tokens = redisTemplate.opsForSet().members(USER_SESSIONS_PREFIX + userId);
        if (tokens == null || tokens.isEmpty()) return 0;

        Duration expiry = Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs());
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (Object t : tokens) {
                    String tok = t.toString();
                    operations.delete(PREFIX + tok);
                    operations.opsForValue().set(REVOKED_PREFIX + tok, "1", expiry);
                }
                operations.delete(USER_SESSIONS_PREFIX + userId);
                return null;
            }
        });
        log.info("Revoked {} session(s) for user [{}]", tokens.size(), userId);
        return tokens.size();
    }


    private boolean isRevoked(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_PREFIX + token));
    }

    public record RotationResult(Long userId, String newToken) {}
}
