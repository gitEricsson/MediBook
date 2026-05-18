package com.medibook.domain.user.service;

import com.medibook.infrastructure.metrics.TokenMetrics;
import com.medibook.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Refresh Token Rotation Tests")
class RefreshTokenRotationTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private TokenMetrics tokenMetrics;

    @Mock
    @SuppressWarnings("unchecked")
    private ValueOperations<String, Object> valueOperations;

    @Mock
    @SuppressWarnings("unchecked")
    private SetOperations<String, Object> setOperations;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        lenient().when(jwtTokenProvider.getRefreshTokenExpirationMs()).thenReturn(604800000L);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    @DisplayName("Should create a new refresh token")
    void testCreateRefreshToken() {
        String token = refreshTokenService.createRefreshToken(1L);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        verify(redisTemplate, times(1)).executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
    }

    @Test
    @DisplayName("Should validate refresh token and return user ID")
    void testValidateAndGetUserId_Success() {
        String token = "test_refresh_token";
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.get(anyString())).thenReturn("1");

        Long userId = refreshTokenService.validateAndGetUserId(token);

        assertEquals(1L, userId);
        verify(valueOperations, times(1)).get(anyString());
    }

    @Test
    @DisplayName("Should throw exception for revoked token")
    void testValidateAndGetUserId_Revoked() {
        String token = "revoked_token";
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        assertThrows(RuntimeException.class, () -> {
            refreshTokenService.validateAndGetUserId(token);
        });
    }

    @Test
    @DisplayName("Should throw exception for expired token")
    void testValidateAndGetUserId_Expired() {
        String token = "expired_token";
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThrows(RuntimeException.class, () -> {
            refreshTokenService.validateAndGetUserId(token);
        });
    }

    @Test
    @DisplayName("Should rotate token and mark old one as revoked")
    void testRotate_Success() {
        String oldToken = "old_refresh_token";
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.get(anyString())).thenReturn("1");
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        RefreshTokenService.RotationResult result = refreshTokenService.rotate(oldToken);

        assertNotNull(result);
        assertEquals(1L, result.userId());
        assertNotNull(result.newToken());
        assertNotEquals(oldToken, result.newToken());
        verify(redisTemplate, times(1)).executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
        verify(tokenMetrics, times(1)).recordTokenRotation(1L);
    }

    @Test
    @DisplayName("Should throw exception on concurrent rotation attempt")
    void testRotate_ConcurrentAttempt() {
        String token = "token_being_rotated";
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        assertThrows(RuntimeException.class, () -> {
            refreshTokenService.rotate(token);
        });
        verify(redisTemplate, never()).executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
    }

    @Test
    @DisplayName("Should revoke token with reason tracking")
    void testRevoke_WithReason() {
        String token = "token_to_revoke";
        when(valueOperations.get(anyString())).thenReturn("1");

        refreshTokenService.revoke(token, "user_logout");

        verify(redisTemplate, times(1)).executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
        verify(tokenMetrics, times(1)).recordTokenRevocation(1L, "user_logout");
    }

    @Test
    @DisplayName("Should revoke all tokens for user with reason")
    void testRevokeAllForUser_WithReason() {
        Long userId = 1L;
        when(setOperations.members(anyString())).thenReturn(
                java.util.Set.of("token1_hash", "token2_hash", "token3_hash")
        );

        int revokedCount = refreshTokenService.revokeAllForUser(userId, "session_timeout");

        assertEquals(3, revokedCount);
        verify(redisTemplate, times(1)).executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
        verify(tokenMetrics, times(1)).recordTokenRevocation(userId, "session_timeout");
    }

    @Test
    @DisplayName("Should handle empty token set on revoke all")
    void testRevokeAllForUser_NoTokens() {
        Long userId = 1L;
        when(redisTemplate.opsForSet().members(anyString())).thenReturn(null);

        int revokedCount = refreshTokenService.revokeAllForUser(userId, "admin_logout");

        assertEquals(0, revokedCount);
        verify(redisTemplate, never()).executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
    }
}
