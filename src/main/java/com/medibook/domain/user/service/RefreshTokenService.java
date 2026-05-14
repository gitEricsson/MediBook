package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.infrastructure.metrics.TokenMetrics;
import com.medibook.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Manages opaque refresh tokens stored in Redis.
 *
     * Token layout uses SHA-256 token hashes, never raw refresh token values:
     *   refresh:{tokenHash}  -> userId  (expires after refresh-token TTL)
     *   revoked:{tokenHash}  -> "1"     (kept for the same TTL to detect re-use after rotation)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String PREFIX               = "refresh:";
    private static final String REVOKED_PREFIX       = "revoked:";
    private static final String USER_SESSIONS_PREFIX = "user_sessions:";
    private static final String ROTATION_LOCK_PREFIX = "refresh_lock:";
    private static final Duration ROTATION_LOCK_TTL  = Duration.ofSeconds(10);

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtTokenProvider              tokenProvider;
    private final TokenMetrics                  tokenMetrics;


    @SuppressWarnings("unchecked")
    public String createRefreshToken(Long userId) {
        String   token  = UUID.randomUUID().toString();
        String   tokenHash = hashToken(token);
        Duration expiry = Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs());

        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.opsForValue().set(PREFIX + tokenHash, String.valueOf(userId), expiry);
                operations.opsForSet().add(USER_SESSIONS_PREFIX + userId, tokenHash);
                operations.expire(USER_SESSIONS_PREFIX + userId, expiry);
                return null;
            }
        });
        return token;
    }


    public Long validateAndGetUserId(String token) {
        String tokenHash = hashToken(token);
        if (isRevokedHash(tokenHash)) {
            throw new MediBookException(
                    "Refresh token has been revoked", HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED");
        }
        Object userId = redisTemplate.opsForValue().get(PREFIX + tokenHash);
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
        String oldHash = hashToken(oldToken);
        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(ROTATION_LOCK_PREFIX + oldHash, "1", ROTATION_LOCK_TTL);
        if (Boolean.FALSE.equals(lockAcquired)) {
            throw new MediBookException(
                    "Refresh token is already being rotated", HttpStatus.UNAUTHORIZED, "TOKEN_REUSE_DETECTED");
        }

        try {
            Long   userId     = validateAndGetUserId(oldToken);
            String newToken   = UUID.randomUUID().toString();
            String newHash    = hashToken(newToken);
            Duration expiry   = Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs());

            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    operations.delete(PREFIX + oldHash);
                    operations.opsForValue().set(REVOKED_PREFIX + oldHash, "1", expiry);
                    operations.opsForValue().set(PREFIX + newHash, String.valueOf(userId), expiry);
                    operations.opsForSet().remove(USER_SESSIONS_PREFIX + userId, oldHash);
                    operations.opsForSet().add(USER_SESSIONS_PREFIX + userId, newHash);
                    operations.expire(USER_SESSIONS_PREFIX + userId, expiry);
                    return null;
                }
            });
            return new RotationResult(userId, newToken);
        } finally {
            redisTemplate.delete(ROTATION_LOCK_PREFIX + oldHash);
        }
    }


    public void revoke(String token) {
        revoke(token, "user_logout");
    }

    public void revoke(String token, String reason) {
        String tokenHash = hashToken(token);
        Long userId = null;
        try {
            Object stored = redisTemplate.opsForValue().get(PREFIX + tokenHash);
            if (stored != null) userId = Long.parseLong(stored.toString());
        } catch (Exception ignored) {}

        final Long resolvedUserId = userId;
        Duration expiry = Duration.ofMillis(tokenProvider.getRefreshTokenExpirationMs());
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.delete(PREFIX + tokenHash);
                operations.opsForValue().set(REVOKED_PREFIX + tokenHash, "1", expiry);
                if (resolvedUserId != null) {
                    operations.opsForSet().remove(USER_SESSIONS_PREFIX + resolvedUserId, tokenHash);
                }
                return null;
            }
        });

        if (resolvedUserId != null) {
            tokenMetrics.recordTokenRevocation(resolvedUserId, reason);
        }
    }

    /**
     * Revokes all active refresh tokens for a user — used by admin force-logout.
     */
    @SuppressWarnings("unchecked")
    public int revokeAllForUser(Long userId) {
        return revokeAllForUser(userId, "admin_force_logout");
    }

    /**
     * Revokes all active refresh tokens for a user with a specific reason.
     * Used by: admin force-logout, session timeout, suspicious activity detection, etc.
     */
    @SuppressWarnings("unchecked")
    public int revokeAllForUser(Long userId, String reason) {
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
        log.info("Revoked {} session(s) for user [{}] — reason: {}", tokens.size(), userId, reason);
        tokenMetrics.recordTokenRevocation(userId, reason);
        return tokens.size();
    }


    private boolean isRevokedHash(String tokenHash) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_PREFIX + tokenHash));
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    public record RotationResult(Long userId, String newToken) {}
}
