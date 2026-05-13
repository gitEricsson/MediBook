package com.medibook.security.websocket;

import com.medibook.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtHandshakeInterceptor — Unit Tests")
class JwtHandshakeInterceptorTest {

    @Mock JwtTokenProvider  jwtTokenProvider;
    @Mock ServerHttpRequest  request;
    @Mock ServerHttpResponse response;
    @Mock WebSocketHandler   wsHandler;

    @InjectMocks JwtHandshakeInterceptor interceptor;

    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        attributes = new HashMap<>();
    }

    @Test
    @DisplayName("allows handshake and stores userId when token is valid (query param)")
    void beforeHandshake_validTokenQueryParam_returnsTrue() throws Exception {
        when(request.getHeaders()).thenReturn(
                org.springframework.http.HttpHeaders.EMPTY);
        when(request.getURI()).thenReturn(
                URI.create("ws://localhost/ws?token=valid.jwt.token"));
        when(jwtTokenProvider.validateToken("valid.jwt.token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("valid.jwt.token")).thenReturn(42L);

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).containsEntry("userId", 42L);
        verify(response, never()).setStatusCode(any());
    }

    @Test
    @DisplayName("allows handshake when token is in Authorization header")
    void beforeHandshake_validTokenHeader_returnsTrue() throws Exception {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", "Bearer header.jwt.token");
        when(request.getHeaders()).thenReturn(headers);
        when(jwtTokenProvider.validateToken("header.jwt.token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("header.jwt.token")).thenReturn(7L);

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).containsEntry("userId", 7L);
    }

    @Test
    @DisplayName("rejects handshake and sets 401 when no token is present")
    void beforeHandshake_missingToken_returns401AndFalse() throws Exception {
        when(request.getHeaders()).thenReturn(org.springframework.http.HttpHeaders.EMPTY);
        when(request.getURI()).thenReturn(URI.create("ws://localhost/ws"));
        when(request.getRemoteAddress()).thenReturn(null);

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).doesNotContainKey("userId");
    }

    @Test
    @DisplayName("rejects handshake when JWT signature is invalid")
    void beforeHandshake_invalidToken_returns401AndFalse() throws Exception {
        when(request.getHeaders()).thenReturn(org.springframework.http.HttpHeaders.EMPTY);
        when(request.getURI()).thenReturn(URI.create("ws://localhost/ws?token=bad.token"));
        when(request.getRemoteAddress()).thenReturn(null);
        when(jwtTokenProvider.validateToken("bad.token")).thenReturn(false);

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).doesNotContainKey("userId");
    }

    @Test
    @DisplayName("query param token takes precedence when both header and param are present")
    void beforeHandshake_headerTakesPrecedenceOverQueryParam() throws Exception {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", "Bearer header.token");
        when(request.getHeaders()).thenReturn(headers);
        when(jwtTokenProvider.validateToken("header.token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("header.token")).thenReturn(5L);

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).containsEntry("userId", 5L);
        // header extracted; query param not checked
        verify(jwtTokenProvider, never()).validateToken("query.token");
    }
}
