package com.medibook.security;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Global Rate Limiting Filter using Resilience4j.
 * Applies "authEndpoint" limits to /auth/** and "apiEndpoint" to others.
 * Limits are configured in application.yml.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterRegistry rateLimiterRegistry;

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        
        String path = request.getRequestURI();
        RateLimiter limiter;

        if (path.startsWith("/api/v1/auth")) {
            limiter = rateLimiterRegistry.rateLimiter("authEndpoint");
        } else {
            limiter = rateLimiterRegistry.rateLimiter("apiEndpoint");
        }

        if (limiter.acquirePermission()) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for path: {}", path);
            handleRateLimitExceeded(response);
        }
    }

    private void handleRateLimitExceeded(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
            {
                "success": false,
                "message": "Too many requests. Please try again later.",
                "errorCode": "RATE_LIMIT_EXCEEDED"
            }
            """);
    }
}
