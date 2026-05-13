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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Redis-backed throttling for the highest-risk MediBook API paths.
 *
 * Redis failures are fail-open so a cache outage does not block legitimate traffic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${medibook.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${medibook.rate-limit.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitRule rule = resolveRule(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String subject = resolveSubject(request, rule.subject());
        String bucket = "rate:%s:%s".formatted(rule.keyPrefix(), subject);

        try {
            Long count = redisTemplate.opsForValue().increment(bucket);
            if (count != null && count == 1) {
                redisTemplate.expire(bucket, WINDOW);
            }
            if (count != null && count > rule.limit()) {
                long retryAfterSeconds = resolveRetryAfterSeconds(bucket);
                log.warn("Rate limit exceeded: subject={} path={} count={} limit={}",
                        subject, request.getRequestURI(), count, rule.limit());
                rejectWithTooManyRequests(response, retryAfterSeconds);
                return;
            }
        } catch (Exception e) {
            log.warn("Rate limiter Redis error - allowing request through: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private RateLimitRule resolveRule(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        // Password reset endpoints - limit to 3 per minute per IP
        if ("POST".equals(method) && (path.contains("/auth/forgot-password") || path.contains("/auth/reset-password"))) {
            return new RateLimitRule("auth:reset:password", 3, SubjectType.IP);
        }
        if ("POST".equals(method) && "/api/v1/auth/login".equals(path)) {
            return new RateLimitRule("auth-login", 10, SubjectType.IP);
        }
        if ("POST".equals(method) && "/api/v1/auth/register".equals(path)) {
            return new RateLimitRule("auth-register", 5, SubjectType.IP);
        }
        if ("POST".equals(method) && "/api/v1/auth/refresh".equals(path)) {
            return new RateLimitRule("auth-refresh", 20, SubjectType.IP);
        }
        if ("POST".equals(method) && "/api/v1/appointments".equals(path)) {
            return new RateLimitRule("appointments-write", 20, SubjectType.USER_OR_IP);
        }
        if ("GET".equals(method) && "/api/v1/doctors/search".equals(path)) {
            return new RateLimitRule("doctor-search", 60, SubjectType.USER_OR_IP);
        }
        if (path.startsWith("/api/v1/") && resolveAuthenticatedUserId() != null) {
            return new RateLimitRule("authenticated-api", 100, SubjectType.USER_OR_IP);
        }
        return null;
    }

    private String resolveSubject(HttpServletRequest request, SubjectType subjectType) {
        if (subjectType == SubjectType.USER_OR_IP) {
            Long userId = resolveAuthenticatedUserId();
            if (userId != null) {
                return "user:" + userId;
            }
        }
        return "ip:" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }

    private Long resolveAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getId();
        }
        return null;
    }

    private long resolveRetryAfterSeconds(String bucket) {
        Long ttl = redisTemplate.getExpire(bucket);
        if (ttl == null || ttl < 1) {
            return WINDOW.toSeconds();
        }
        return ttl;
    }

    private void rejectWithTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.getWriter().write(
                "{\"success\":false,\"message\":\"Too many requests. Please try again later.\",\"errorCode\":\"RATE_LIMIT_EXCEEDED\"}");
    }

    private enum SubjectType {
        IP,
        USER_OR_IP
    }

    private record RateLimitRule(String keyPrefix, int limit, SubjectType subject) {
    }
}
