package com.medibook.security.websocket;

import java.security.Principal;

/**
 * Immutable STOMP principal whose name is the authenticated user's ID as a String.
 * Spring routes convertAndSendToUser(userId, ...) via this principal name.
 */
public record StompPrincipal(String name) implements Principal {
    @Override
    public String getName() {
        return name;
    }
}
