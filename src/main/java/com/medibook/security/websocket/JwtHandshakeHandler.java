package com.medibook.security.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * Creates the STOMP principal from the userId stored by JwtHandshakeInterceptor.
 * The principal name (userId.toString()) is the routing key used by
 * SimpMessagingTemplate.convertAndSendToUser().
 */
@Slf4j
@Component
public class JwtHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        Long userId = (Long) attributes.get("userId");
        if (userId == null) {
            log.warn("No userId in WebSocket session attributes — rejecting connection");
            return null;
        }
        return new StompPrincipal(String.valueOf(userId));
    }
}
