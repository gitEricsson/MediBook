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
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService — Unit Tests")
class RefreshTokenServiceTest {
    private static String keyStartsWith(String prefix) {
        return argThat((String k) -> k.startsWith(prefix));
    }

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Mock JwtTokenProvider tokenProvider;

    @InjectMocks RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(tokenProvider.getRefreshTokenExpirationMs()).thenReturn(604_800_000L);
    }

    @Test
    @DisplayName("createRefreshToken — stores userId via pipelined write and returns non-blank token")
    void createRefreshToken_storesInRedis() {
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenReturn(List.of());

        String token = refreshTokenService.createRefreshToken(42L);

        assertThat(token).isNotBlank();
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("validateAndGetUserId — valid token returns userId")
    void validateAndGetUserId_valid() {
        when(redisTemplate.hasKey(keyStartsWith("revoked:"))).thenReturn(false);
        when(valueOps.get(keyStartsWith("refresh:"))).thenReturn("42");

        Long userId = refreshTokenService.validateAndGetUserId("some-token");
        assertThat(userId).isEqualTo(42L);
    }

    @Test
    @DisplayName("validateAndGetUserId — revoked token throws UNAUTHORIZED")
    void validateAndGetUserId_revoked_throws() {
        when(redisTemplate.hasKey(keyStartsWith("revoked:"))).thenReturn(true);

        assertThatThrownBy(() -> refreshTokenService.validateAndGetUserId("revoked-token"))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("validateAndGetUserId — expired (null) token throws UNAUTHORIZED")
    void validateAndGetUserId_expired_throws() {
        when(redisTemplate.hasKey(keyStartsWith("revoked:"))).thenReturn(false);
        when(valueOps.get(keyStartsWith("refresh:"))).thenReturn(null);

        assertThatThrownBy(() -> refreshTokenService.validateAndGetUserId("expired-token"))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("revoke — executes delete + revocation marker via pipeline")
    void revoke_deletesAndMarks() {
        when(valueOps.get(keyStartsWith("refresh:"))).thenReturn(null); // token not found in Redis
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenReturn(List.of());

        refreshTokenService.revoke("some-token");

        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }


    @Test
    @DisplayName("rotate — valid token validates, pipelines revoke+issue, returns new RotationResult")
    void rotate_valid_invalidatesOldAndReturnsNewToken() {
        when(redisTemplate.hasKey(keyStartsWith("revoked:"))).thenReturn(false);
        when(valueOps.get(keyStartsWith("refresh:"))).thenReturn("42");
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenReturn(List.of());

        RefreshTokenService.RotationResult result = refreshTokenService.rotate("old-token");

        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.newToken()).isNotBlank();
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("rotate — revoked token throws TOKEN_REVOKED before touching pipeline")
    void rotate_revokedToken_throws() {
        when(redisTemplate.hasKey(keyStartsWith("revoked:"))).thenReturn(true);

        assertThatThrownBy(() -> refreshTokenService.rotate("revoked-token"))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("revoked");
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("rotate — expired (missing) token throws TOKEN_INVALID before touching pipeline")
    void rotate_expiredToken_throws() {
        when(redisTemplate.hasKey(keyStartsWith("revoked:"))).thenReturn(false);
        when(valueOps.get(keyStartsWith("refresh:"))).thenReturn(null);

        assertThatThrownBy(() -> refreshTokenService.rotate("expired-token"))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("expired");
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }
}
