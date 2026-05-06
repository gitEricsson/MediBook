package com.medibook.jobs;

import com.medibook.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges revoked refresh token records from Redis.
 * Revoked tokens have a TTL matching the original refresh window (7 days),
 * so this job is advisory and logs residual revocation record counts.
 * Runs every Sunday at 03:00.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupJob {

    private final RedisTemplate<String, Object> redisTemplate;

    @Scheduled(cron = "0 0 3 ? * SUN")   // Sunday 03:00
    public void cleanRevokedTokens() {
        var revokedKeys = redisTemplate.keys("revoked:*");
        long count = revokedKeys != null ? revokedKeys.size() : 0;
        log.info("TokenCleanupJob: {} revoked token records in Redis (TTL-managed)", count);
    }
}
