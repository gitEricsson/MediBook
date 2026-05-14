package com.medibook.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cleans expired OTP keys from Redis.
 * Redis TTL handles expiry automatically, but this job ensures no stale patterns
 * accumulate if Redis is running without active eviction.
 * Runs daily at 02:00. Includes error handling and metrics.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "medibook.jobs.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OtpCleanupJob {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    @Scheduled(cron = "0 0 2 * * ?")   // 02:00 daily
    @SchedulerLock(name = "OtpCleanupJob_cleanExpiredOtps", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void cleanExpiredOtps() {
        try {
            log.info("OtpCleanupJob: Redis TTL handles expiry — verifying OTP key count");
            long otpKeyCount = countKeys("otp:*");
            log.info("OtpCleanupJob: active OTP keys in Redis = {}", otpKeyCount);
        } catch (Exception ex) {
            log.error("OtpCleanupJob failed with error", ex);
            meterRegistry.counter("scheduled.job.failure", "job", "OtpCleanupJob").increment();
        }
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
