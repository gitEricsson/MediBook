package com.medibook.domain.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class AppointmentIdempotencyService {

    private static final Duration RESPONSE_TTL = Duration.ofHours(24);
    private static final Duration LOCK_TTL = Duration.ofMinutes(2);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public AppointmentResponse execute(
            Long patientId,
            String idempotencyKey,
            AppointmentRequest request,
            Supplier<AppointmentResponse> action) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return action.get();
        }

        String normalizedKey = idempotencyKey.trim();
        String requestFingerprint = fingerprint(request);
        String cacheKey = "idempotency:appointments:" + patientId + ":" + normalizedKey;
        String lockKey = cacheKey + ":lock";

        CachedAppointmentBooking cached = readCached(cacheKey);
        if (cached != null) {
            assertSameRequest(cached.requestFingerprint(), requestFingerprint);
            return cached.response();
        }

        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(lockKey, requestFingerprint, LOCK_TTL);
        if (Boolean.FALSE.equals(lockAcquired)) {
            cached = readCached(cacheKey);
            if (cached != null) {
                assertSameRequest(cached.requestFingerprint(), requestFingerprint);
                return cached.response();
            }
            throw new MediBookException(
                    "A request with this Idempotency-Key is already in progress",
                    HttpStatus.CONFLICT,
                    "IDEMPOTENT_REQUEST_IN_PROGRESS");
        }

        try {
            AppointmentResponse response = action.get();
            redisTemplate.opsForValue().set(
                    cacheKey,
                    new CachedAppointmentBooking(requestFingerprint, response),
                    RESPONSE_TTL);
            return response;
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private CachedAppointmentBooking readCached(String cacheKey) {
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached == null) {
            return null;
        }
        return objectMapper.convertValue(cached, CachedAppointmentBooking.class);
    }

    private void assertSameRequest(String existingFingerprint, String requestFingerprint) {
        if (!requestFingerprint.equals(existingFingerprint)) {
            throw new MediBookException(
                    "This Idempotency-Key was already used for a different appointment request",
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED");
        }
    }

    private String fingerprint(AppointmentRequest request) {
        return "%s|%s|%s|%s|%s".formatted(
                request.getDoctorId(),
                request.getScheduledAt(),
                request.getDurationMins(),
                request.getType(),
                request.getReason() == null ? "" : request.getReason().trim());
    }

    record CachedAppointmentBooking(String requestFingerprint, AppointmentResponse response) {
    }
}
