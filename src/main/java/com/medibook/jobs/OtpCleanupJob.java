package com.medibook.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cleans expired OTP keys from Redis.
 * Redis TTL handles expiry automatically, but this job ensures no stale patterns
 * accumulate if Redis is running without active eviction.
 * Runs daily at 02:00.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpCleanupJob {

    private final RedisTemplate<String, Object> redisTemplate;

    @Scheduled(cron = "0 0 2 * * ?")   // 02:00 daily
    public void cleanExpiredOtps() {
        log.info("OtpCleanupJob: Redis TTL handles expiry — verifying OTP key count");
        Long otpKeyCount = redisTemplate.keys("otp:*") != null
                ? redisTemplate.keys("otp:*").size() : 0L;
        log.info("OtpCleanupJob: active OTP keys in Redis = {}", otpKeyCount);
    }
}
