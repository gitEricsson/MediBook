package com.medibook.security;

import com.medibook.domain.user.service.SessionTimeoutService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Session Timeout Filter Tests")
class SessionTimeoutFilterTest {

    @Mock
    private SessionTimeoutService sessionTimeoutService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @Mock
    private UserPrincipal userPrincipal;

    @InjectMocks
    private SessionTimeoutFilter sessionTimeoutFilter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    @DisplayName("Should skip filter for health check endpoints")
    void testShouldSkip_HealthEndpoint() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/health");
        sessionTimeoutFilter.doFilterInternal(request, response, filterChain);
        verify(filterChain, times(1)).doFilter(request, response);
        verify(sessionTimeoutService, never()).isSessionValid(anyLong());
    }

    @Test
    @DisplayName("Should skip filter for authentication endpoints")
    void testShouldSkip_AuthEndpoint() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        sessionTimeoutFilter.doFilterInternal(request, response, filterChain);
        verify(filterChain, times(1)).doFilter(request, response);
        verify(sessionTimeoutService, never()).isSessionValid(anyLong());
    }

    @Test
    @DisplayName("Should skip filter for unauthenticated requests")
    void testShouldSkip_NoAuthentication() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/v1/users");
        when(securityContext.getAuthentication()).thenReturn(null);
        sessionTimeoutFilter.doFilterInternal(request, response, filterChain);
        verify(filterChain, times(1)).doFilter(request, response);
        verify(sessionTimeoutService, never()).isSessionValid(anyLong());
    }

    @Test
    @DisplayName("Should allow request when session is valid")
    void testShouldAllowRequest_ValidSession() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/v1/appointments");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(userPrincipal);
        when(userPrincipal.getId()).thenReturn(1L);
        when(sessionTimeoutService.isSessionValid(1L)).thenReturn(true);
        sessionTimeoutFilter.doFilterInternal(request, response, filterChain);
        verify(sessionTimeoutService, times(1)).isSessionValid(1L);
        verify(sessionTimeoutService, times(1)).updateActivity(1L);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("Should return 401 when session is expired")
    void testShouldRejectRequest_ExpiredSession() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/v1/appointments");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(userPrincipal);
        when(userPrincipal.getId()).thenReturn(1L);
        when(sessionTimeoutService.isSessionValid(1L)).thenReturn(false);

        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(writer);
        sessionTimeoutFilter.doFilterInternal(request, response, filterChain);
        verify(sessionTimeoutService, times(1)).isSessionValid(1L);
        verify(response, times(1)).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(response, times(1)).setContentType(MediaType.APPLICATION_JSON_VALUE);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Should handle exceptions gracefully (fail-open)")
    void testHandleException_FailOpen() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/v1/appointments");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(userPrincipal);
        when(userPrincipal.getId()).thenReturn(1L);
        when(sessionTimeoutService.isSessionValid(1L)).thenThrow(new RuntimeException("DB error"));
        sessionTimeoutFilter.doFilterInternal(request, response, filterChain);
        // On error, should allow request to proceed (fail-open for availability)
        verify(filterChain, times(1)).doFilter(request, response);
    }
}
