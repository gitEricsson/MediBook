package com.medibook.security.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("WebSocketAuthChannelInterceptor — Unit Tests")
class WebSocketAuthChannelInterceptorTest {

    private WebSocketAuthChannelInterceptor interceptor;
    private MessageChannel                  channel;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthChannelInterceptor();
        channel     = mock(MessageChannel.class);
    }

    private Message<?> buildMessage(StompCommand command, String destination, StompPrincipal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) accessor.setDestination(destination);
        if (principal   != null) accessor.setUser(principal);
        accessor.setSessionId("test-session");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("CONNECT — passes through when principal is present")
    void connect_withPrincipal_passes() {
        Message<?> msg = buildMessage(StompCommand.CONNECT, null, new StompPrincipal("42"));
        assertThatNoException().isThrownBy(() -> interceptor.preSend(msg, channel));
    }

    @Test
    @DisplayName("CONNECT — throws AccessDeniedException when no principal (unauthenticated)")
    void connect_noPrincipal_throwsAccessDenied() {
        Message<?> msg = buildMessage(StompCommand.CONNECT, null, null);
        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("SUBSCRIBE — /user/queue/notifications is allowed for authenticated user")
    void subscribe_ownUserQueuePath_allowed() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/user/queue/notifications", new StompPrincipal("42"));
        assertThatNoException().isThrownBy(() -> interceptor.preSend(msg, channel));
    }

    @Test
    @DisplayName("SUBSCRIBE — /user/42/queue/notifications (explicit own id) is allowed")
    void subscribe_explicitOwnId_allowed() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/user/42/queue/notifications", new StompPrincipal("42"));
        assertThatNoException().isThrownBy(() -> interceptor.preSend(msg, channel));
    }

    @Test
    @DisplayName("SUBSCRIBE — /user/{otherId}/queue/notifications is rejected")
    void subscribe_otherUserQueue_throwsAccessDenied() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/user/99/queue/notifications", new StompPrincipal("42"));
        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    @DisplayName("SUBSCRIBE — non-user destinations (e.g. /topic/public) pass through")
    void subscribe_nonUserDestination_passes() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/topic/public", new StompPrincipal("42"));
        assertThatNoException().isThrownBy(() -> interceptor.preSend(msg, channel));
    }

    @Test
    @DisplayName("SUBSCRIBE — without principal throws AccessDeniedException")
    void subscribe_noPrincipal_throwsAccessDenied() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/user/queue/notifications", null);
        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("SUBSCRIBE — /topic/conversations/{id} is rejected (private fan-out lives on /user/queue)")
    void subscribe_privateConversationsTopic_blocked() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/topic/conversations/42", new StompPrincipal("42"));
        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("SUBSCRIBE — /topic/chat/* is rejected")
    void subscribe_chatTopic_blocked() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/topic/chat/anything", new StompPrincipal("42"));
        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("SUBSCRIBE — /topic/copilot/* is rejected")
    void subscribe_copilotTopic_blocked() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/topic/copilot/123", new StompPrincipal("42"));
        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("SUBSCRIBE — public /topic/announcements stays allowed")
    void subscribe_publicTopic_allowed() {
        Message<?> msg = buildMessage(StompCommand.SUBSCRIBE,
                "/topic/announcements", new StompPrincipal("42"));
        assertThatNoException().isThrownBy(() -> interceptor.preSend(msg, channel));
    }

    @Test
    @DisplayName("SEND frames pass through without restriction")
    void send_passesThroughUnrestricted() {
        Message<?> msg = buildMessage(StompCommand.SEND,
                "/app/something", new StompPrincipal("42"));
        assertThatNoException().isThrownBy(() -> interceptor.preSend(msg, channel));
    }
}
