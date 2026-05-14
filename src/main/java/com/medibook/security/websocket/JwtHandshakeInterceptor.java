package com.medibook.security.websocket;

import com.medibook.audit.service.AuditLogService;
import com.medibook.messaging.event.AuditEvent;
import com.medibook.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Validates the JWT token during the HTTP → WebSocket upgrade.
 * Supports two transport modes:
 *   1. Authorization: Bearer <token>  (preferred — headers supported by server-side clients)
 *   2. ws://host/ws?token=<token>     (browser STOMP clients that cannot set upgrade headers)
 *
 * Stores userId in session attributes so JwtHandshakeHandler can build the Principal.
 * Rejects unauthenticated handshakes with HTTP 401 before the WebSocket session opens.
 * Logs all successful WebSocket connections for audit trail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogService auditLogService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = extractToken(request);

        if (!StringUtils.hasText(token) || !jwtTokenProvider.validateToken(token)) {
            log.warn("WebSocket handshake rejected — missing or invalid JWT; remote={}",
                    request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        attributes.put("userId", userId);
        log.debug("WebSocket handshake accepted; userId={}", userId);

        // Audit log for successful WebSocket connection
        try {
            String remoteAddr = request.getRemoteAddress() != null
                    ? request.getRemoteAddress().toString()
                    : "unknown";
            log.info("audit=WEBSOCKET_CONNECTED userId={} remoteAddr={}", userId, remoteAddr);

            // Persist audit event asynchronously
            auditLogService.persist(
                    AuditEvent.builder()
                            .eventId(UUID.randomUUID().toString())
                            .action("WEBSOCKET_CONNECTED")
                            .actorId(userId)
                            .resourceType("WEBSOCKET_SESSION")
                            .occurredAt(LocalDateTime.now())
                            .build()
            );
        } catch (Exception ex) {
            log.error("Failed to audit WebSocket connection for userId: {}", userId, ex);
            // Don't fail the handshake due to audit logging errors
        }

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception ex) {
        // no-op
    }

    private String extractToken(ServerHttpRequest request) {
        // 1. Authorization header
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // 2. Query param (?token=...) — browser WebSocket/STOMP clients
        String query = request.getURI().getQuery();
        if (StringUtils.hasText(query)) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    return param.substring("token=".length());
                }
            }
        }
        return null;
    }
}
