package com.medibook.security.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * STOMP-level security interceptor. Runs after JwtHandshakeInterceptor so the
 * Principal is already established. Enforces two rules:
 *
 *  1. CONNECT — principal must be present (JWT was valid at handshake).
 *  2. SUBSCRIBE — users may not subscribe to another user's private queue.
 *
 * Admin topics (/topic/admin/**) are not subscribed to by the browser in normal
 * flow; they are server-to-clients broadcast paths used internally. If a client
 * attempts to subscribe, the method-security layer on the message handlers will
 * deny it, so no extra check is needed here.
 */
@Slf4j
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        switch (accessor.getCommand() == null ? StompCommand.SEND : accessor.getCommand()) {
            case CONNECT -> requirePrincipal(accessor);
            case SUBSCRIBE -> validateSubscription(accessor);
            default -> { /* all other frames pass through */ }
        }

        return message;
    }

    private void requirePrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            log.warn("STOMP CONNECT rejected — no authenticated principal in session");
            throw new AccessDeniedException("WebSocket connection requires authentication");
        }
        log.debug("STOMP CONNECT accepted; user={}", accessor.getUser().getName());
    }

    private void validateSubscription(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        String destination = accessor.getDestination();

        if (user == null) {
            throw new AccessDeniedException("STOMP SUBSCRIBE requires authentication");
        }
        if (destination == null || !destination.startsWith("/user/")) {
            return; // non-user destinations are unrestricted at this layer
        }

        // /user/queue/...  — Spring rewrites to /user/{principalName}/queue/... automatically
        // /user/{otherId}/queue/... — explicit cross-user subscribe attempt; block it
        String afterPrefix = destination.substring("/user/".length());
        String firstSegment = afterPrefix.split("/")[0];

        boolean isOwnQueuePath = firstSegment.equals("queue") || firstSegment.equals(user.getName());
        if (!isOwnQueuePath) {
            log.warn("STOMP SUBSCRIBE blocked; user={} attempted to subscribe to {}",
                    user.getName(), destination);
            throw new AccessDeniedException(
                    "Subscribing to another user's notification queue is forbidden");
        }

        log.debug("STOMP SUBSCRIBE accepted; user={} destination={}", user.getName(), destination);
    }
}
