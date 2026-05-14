package com.medibook.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class SecurityResponseHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);

        // Always set security headers for all responses
        setSecurityHeaders(response);

        if (isAuthenticatedApiRequest(request)) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
        }
    }

    private void setSecurityHeaders(HttpServletResponse response) {
        // Content Security Policy
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: https:; " +
                "font-src 'self'; " +
                "connect-src 'self' wss: https:; " +
                "frame-ancestors 'none'");

        // Prevent MIME type sniffing
        response.setHeader("X-Content-Type-Options", "nosniff");

        // Clickjacking protection
        response.setHeader("X-Frame-Options", "DENY");

        // XSS protection (0 = modern browsers rely on CSP)
        response.setHeader("X-XSS-Protection", "0");

        // Restrict browser features
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        // HSTS (optional: only enable if HTTPS is enforced)
        // response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
    }

    private boolean isAuthenticatedApiRequest(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/v1/")) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal;
    }
}
