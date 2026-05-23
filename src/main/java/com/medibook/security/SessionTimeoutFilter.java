package com.medibook.security;

import com.medibook.domain.user.service.SessionTimeoutService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Session Timeout Filter — enforces inactivity-based session termination.
 *
 * For each authenticated request:
 * 1. Extract the authenticated user ID from SecurityContext
 * 2. Check if the session has exceeded the inactivity timeout (default: 30 min)
 * 3. If timed out: revoke all active tokens and return 401 SESSION_EXPIRED
 * 4. If active: update lastActivityAt and allow request to proceed
 *
 * Skips unauthenticated endpoints, health checks, and metrics.
 *
 * Ordering: After JwtAuthenticationFilter, so JWT is already validated.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionTimeoutFilter extends OncePerRequestFilter {

    private final SessionTimeoutService sessionTimeoutService;

    private static final Set<String> SKIP_PATHS = new HashSet<>(Arrays.asList(
            "/health",
            "/health/",
            "/actuator/health",
            "/actuator/prometheus",
            "/metrics",
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/2fa/verify",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/email/verify",
            "/api/v1/auth/email/resend",
            "/ws",
            "/swagger-ui",
            "/api-docs",
            "/version"
    ));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestPath = request.getRequestURI();

        if (shouldSkip(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof UserPrincipal)) {
            filterChain.doFilter(request, response);
            return;
        }

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        Long userId = principal.getId();

        try {
            if (!sessionTimeoutService.isSessionValid(userId)) {
                log.warn("Session expired for user [{}] due to inactivity", userId);
                SecurityContextHolder.clearContext();
                sendSessionExpiredResponse(response);
                return;
            }

            sessionTimeoutService.updateActivity(userId);

        } catch (Exception ex) {
            log.error("Error checking session timeout for user [{}]", userId, ex);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Check if the request path should skip session timeout validation.
     */
    private boolean shouldSkip(String path) {
        return SKIP_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Send a 401 SESSION_EXPIRED response.
     */
    private void sendSessionExpiredResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"success\":false," +
                "\"message\":\"Session expired due to inactivity\"," +
                "\"code\":\"SESSION_EXPIRED\",\"timestamp\":\"" +
                System.currentTimeMillis() + "\"}"
        );
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return shouldSkip(request.getRequestURI());
    }
}
