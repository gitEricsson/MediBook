package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService — Unit Tests")
class PasswordResetServiceTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Mock JavaMailSender mailSender;

    @InjectMocks PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }


    @Test
    @DisplayName("createToken — returns a non-blank UUID-format token")
    void createToken_returnsNonBlankToken() {
        String token = passwordResetService.createToken(42L);

        assertThat(token).isNotBlank();
        // UUID v4 format: 8-4-4-4-12 hex chars
        assertThat(token).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("createToken — stores userId under pwd-reset: prefix with 15-minute TTL")
    void createToken_storesUserIdWithCorrectTtl() {
        String token = passwordResetService.createToken(42L);

        verify(valueOps).set(
                argThat(k -> k.startsWith("pwd-reset:") && k.endsWith(token)),
                eq("42"),
                eq(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("createToken — each call generates a unique token")
    void createToken_isUnique() {
        String first  = passwordResetService.createToken(1L);
        String second = passwordResetService.createToken(1L);

        assertThat(first).isNotEqualTo(second);
    }


    @Test
    @DisplayName("validateAndConsume — valid token returns userId and atomically deletes the key")
    void validateAndConsume_validToken_returnsUserIdAndDeletes() {
        when(valueOps.getAndDelete(argThat(k -> k.startsWith("pwd-reset:")))).thenReturn("42");

        Long userId = passwordResetService.validateAndConsume("any-valid-token");

        assertThat(userId).isEqualTo(42L);
        // getAndDelete is the atomic proof — no separate delete call needed
        verify(valueOps).getAndDelete(argThat(k -> k.startsWith("pwd-reset:")));
    }

    @Test
    @DisplayName("validateAndConsume — expired or invalid token throws BAD_REQUEST with RESET_TOKEN_INVALID")
    void validateAndConsume_invalidToken_throws() {
        when(valueOps.getAndDelete(argThat(k -> k.startsWith("pwd-reset:")))).thenReturn(null);

        assertThatThrownBy(() -> passwordResetService.validateAndConsume("stale-token"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(mbe.getErrorCode()).isEqualTo("RESET_TOKEN_INVALID");
                });
    }

    @Test
    @DisplayName("validateAndConsume — token consumed once cannot be replayed (atomic delete)")
    void validateAndConsume_sameTokenTwice_secondCallThrows() {
        when(valueOps.getAndDelete(argThat(k -> k.startsWith("pwd-reset:"))))
                .thenReturn("42")   // first call
                .thenReturn(null);  // second call — key already gone

        Long userId = passwordResetService.validateAndConsume("one-time-token");
        assertThat(userId).isEqualTo(42L);

        assertThatThrownBy(() -> passwordResetService.validateAndConsume("one-time-token"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
