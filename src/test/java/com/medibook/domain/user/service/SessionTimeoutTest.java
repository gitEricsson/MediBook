package com.medibook.domain.user.service;

import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.infrastructure.metrics.TokenMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Session Timeout Service Tests")
class SessionTimeoutTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private TokenMetrics tokenMetrics;

    @InjectMocks
    private SessionTimeoutService sessionTimeoutService;

    private User testUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sessionTimeoutService, "sessionTimeoutMinutes", 30);
        testUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("encoded_password")
                .firstName("Test")
                .lastName("User")
                .role(Role.ROLE_PATIENT)
                .enabled(true)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("Should return true for active session within timeout window")
    void testIsSessionValid_WithinTimeout() {
        // Arrange
        testUser.setLastActivityAt(LocalDateTime.now().minusMinutes(10));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        // Act
        boolean isValid = sessionTimeoutService.isSessionValid(1L);

        // Assert
        assertTrue(isValid);
        verify(userRepository, times(1)).findById(1L);
        verify(refreshTokenService, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("Should return false and revoke tokens for session exceeding timeout")
    void testIsSessionValid_ExceededTimeout() {
        // Arrange
        testUser.setLastActivityAt(LocalDateTime.now().minusMinutes(35)); // 35 minutes ago
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(refreshTokenService.revokeAllForUser(1L, "session_timeout")).thenReturn(1);

        // Act
        boolean isValid = sessionTimeoutService.isSessionValid(1L);

        // Assert
        assertFalse(isValid);
        verify(refreshTokenService, times(1)).revokeAllForUser(1L, "session_timeout");
        verify(tokenMetrics, times(1)).recordSessionTimeout(eq(1L), anyLong());
    }

    @Test
    @DisplayName("Should return false for non-existent user")
    void testIsSessionValid_UserNotFound() {
        // Arrange
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // Act
        boolean isValid = sessionTimeoutService.isSessionValid(999L);

        // Assert
        assertFalse(isValid);
    }

    @Test
    @DisplayName("Should update lastActivityAt to current time")
    void testUpdateActivity() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        // Act
        sessionTimeoutService.updateActivity(1L);

        // Assert
        verify(userRepository, times(1)).findById(1L);
        verify(userRepository, times(1)).save(argThat(user ->
                user.getLastActivityAt() != null &&
                user.getLastActivityAt().isBefore(LocalDateTime.now().plusSeconds(1)) &&
                user.getLastActivityAt().isAfter(LocalDateTime.now().minusSeconds(5))
        ));
    }

    @Test
    @DisplayName("Should return session timeout minutes configuration")
    void testGetSessionTimeoutMinutes() {
        // Act & Assert
        assertEquals(30, sessionTimeoutService.getSessionTimeoutMinutes());
    }

    @Test
    @DisplayName("Should calculate remaining session time correctly")
    void testGetRemainingSessionMinutes() {
        // Arrange
        testUser.setLastActivityAt(LocalDateTime.now().minusMinutes(15));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        // Act
        long remaining = sessionTimeoutService.getRemainingSessionMinutes(1L);

        // Assert
        assertTrue(remaining > 0 && remaining <= 15);
    }

    @Test
    @DisplayName("Should return -1 for remaining time if session exceeded")
    void testGetRemainingSessionMinutes_Exceeded() {
        // Arrange
        testUser.setLastActivityAt(LocalDateTime.now().minusMinutes(35));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        // Act
        long remaining = sessionTimeoutService.getRemainingSessionMinutes(1L);

        // Assert
        assertEquals(-1, remaining);
    }
}
