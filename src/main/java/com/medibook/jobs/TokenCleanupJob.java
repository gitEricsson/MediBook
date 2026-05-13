package com.medibook.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
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
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class TokenCleanupJob {

    private final RedisTemplate<String, Object> redisTemplate;

    @Scheduled(cron = "0 0 3 ? * SUN")   // Sunday 03:00
    public void cleanRevokedTokens() {
        long count = countKeys("revoked:*");
        log.info("TokenCleanupJob: {} revoked token records in Redis (TTL-managed)", count);
    }

    private long countKeys(String pattern) {
        return redisTemplate.execute((RedisCallback<Long>) connection -> {
            long count = 0L;
            try (var cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(500).build())) {
                while (cursor.hasNext()) {
                    cursor.next();
                    count++;
                }
            }
            return count;
        });
    }
}
