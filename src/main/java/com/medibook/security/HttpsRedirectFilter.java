package com.medibook.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * HTTPS redirect filter that enforces HTTPS in production.
 * Redirects HTTP requests to HTTPS with a 301 (permanent) redirect.
 * Skips /health/* endpoints to avoid interfering with load balancer probes.
 *
 * Activated only when app.security.https-redirect=true (typically in production).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.security.https-redirect", havingValue = "true")
public class HttpsRedirectFilter extends OncePerRequestFilter {

    private static final String[] SKIP_PATTERNS = {"/health/"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        // Skip health check endpoints
        if (shouldSkip(requestUri)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check if request is HTTP and not already HTTPS via X-Forwarded-Proto
        String scheme = request.getScheme();
        String forwardedProto = request.getHeader("X-Forwarded-Proto");

        if ("http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(forwardedProto)) {
            String httpsUrl = buildHttpsUrl(request);
            log.debug("Redirecting HTTP request to HTTPS: {} -> {}", request.getRequestURL(), httpsUrl);
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", httpsUrl);
            response.getWriter().flush();
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean shouldSkip(String requestUri) {
        for (String pattern : SKIP_PATTERNS) {
            if (requestUri.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String buildHttpsUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder("https://");
        url.append(request.getServerName());

        // Only append port if it's not the default HTTPS port (443)
        if (request.getServerPort() != 443) {
            url.append(":").append(request.getServerPort());
        }

        url.append(request.getRequestURI());
        if (request.getQueryString() != null) {
            url.append("?").append(request.getQueryString());
        }

        return url.toString();
    }
}
