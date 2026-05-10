package com.medibook.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Per-IP rate limiter backed by Redis fixed-window counters.
 *
 * Auth-sensitive paths (login, register, forgot-password) → 5 req/min per IP.
 * All other API paths → 100 req/min per IP.
 *
 * Redis unavailability is treated as a pass-through (fail-open) to avoid
 * blocking legitimate traffic during a cache outage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> AUTH_SENSITIVE_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/forgot-password"
    );

    private static final int      AUTH_LIMIT  = 5;
    private static final int      API_LIMIT   = 100;
    private static final Duration WINDOW      = Duration.ofMinutes(1);

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${medibook.rate-limit.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String ip   = resolveClientIp(request);

        boolean sensitive = AUTH_SENSITIVE_PATHS.contains(path);
        String  bucket    = sensitive ? "rate:auth:" + ip : "rate:api:" + ip;
        int     limit     = sensitive ? AUTH_LIMIT : API_LIMIT;

        try {
            Long count = redisTemplate.opsForValue().increment(bucket);
            if (count != null && count == 1) {
                redisTemplate.expire(bucket, WINDOW);
            }
            if (count != null && count > limit) {
                log.warn("Rate limit exceeded: ip={} path={} count={}", ip, path, count);
                rejectWithTooManyRequests(response);
                return;
            }
        } catch (Exception e) {
            log.warn("Rate limiter Redis error — allowing request through: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private void rejectWithTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"Too many requests. Please try again later.\",\"errorCode\":\"RATE_LIMIT_EXCEEDED\"}");
    }
}