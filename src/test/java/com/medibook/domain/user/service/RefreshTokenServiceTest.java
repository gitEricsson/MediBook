package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService — Unit Tests")
class RefreshTokenServiceTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Mock JwtTokenProvider tokenProvider;

    @InjectMocks RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(tokenProvider.getRefreshTokenExpirationMs()).thenReturn(604_800_000L);
    }

    @Test
    @DisplayName("createRefreshToken — stores userId in Redis with TTL")
    void createRefreshToken_storesInRedis() {
        String token = refreshTokenService.createRefreshToken(42L);
        assertThat(token).isNotBlank();
        verify(valueOps).set(argThat(k -> k.startsWith("refresh:")), eq("42"), any(Duration.class));
    }

    @Test
    @DisplayName("validateAndGetUserId — valid token returns userId")
    void validateAndGetUserId_valid() {
        when(redisTemplate.hasKey(argThat(k -> k.startsWith("revoked:")))).thenReturn(false);
        when(valueOps.get(argThat(k -> k.startsWith("refresh:")))).thenReturn("42");

        Long userId = refreshTokenService.validateAndGetUserId("some-token");
        assertThat(userId).isEqualTo(42L);
    }

    @Test
    @DisplayName("validateAndGetUserId — revoked token throws UNAUTHORIZED")
    void validateAndGetUserId_revoked_throws() {
        when(redisTemplate.hasKey(argThat(k -> k.startsWith("revoked:")))).thenReturn(true);

        assertThatThrownBy(() -> refreshTokenService.validateAndGetUserId("revoked-token"))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("validateAndGetUserId — expired (null) token throws UNAUTHORIZED")
    void validateAndGetUserId_expired_throws() {
        when(redisTemplate.hasKey(argThat(k -> k.startsWith("revoked:")))).thenReturn(false);
        when(valueOps.get(argThat(k -> k.startsWith("refresh:")))).thenReturn(null);

        assertThatThrownBy(() -> refreshTokenService.validateAndGetUserId("expired-token"))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("revoke — deletes refresh key and sets revoked marker")
    void revoke_deletesAndMarks() {
        when(redisTemplate.hasKey(argThat(k -> k.startsWith("revoked:")))).thenReturn(false);
        when(valueOps.get(argThat(k -> k.startsWith("refresh:")))).thenReturn("42");

        refreshTokenService.revoke("some-token");

        verify(redisTemplate).delete(argThat(k -> k.startsWith("refresh:")));
        verify(valueOps).set(argThat(k -> k.startsWith("revoked:")), eq("1"), any(Duration.class));
    }
}
