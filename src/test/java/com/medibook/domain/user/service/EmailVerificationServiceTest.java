package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.mail.TransactionalEmailService;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationService — Unit Tests")
class EmailVerificationServiceTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Mock TransactionalEmailService transactionalEmailService;

    @InjectMocks EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }


    @Test
    @DisplayName("createToken — returns a non-blank UUID-format token")
    void createToken_returnsNonBlankToken() {
        String token = emailVerificationService.createToken(7L);

        assertThat(token).isNotBlank();
        assertThat(token).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("createToken — stores userId under email-verify: prefix with 24-hour TTL")
    void createToken_storesUserIdWithCorrectTtl() {
        String token = emailVerificationService.createToken(7L);

        verify(valueOps).set(
                argThat(k -> k.startsWith("email-verify:") && k.endsWith(token)),
                eq("7"),
                eq(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("createToken — each call generates a unique token")
    void createToken_isUnique() {
        String first  = emailVerificationService.createToken(1L);
        String second = emailVerificationService.createToken(1L);

        assertThat(first).isNotEqualTo(second);
    }


    @Test
    @DisplayName("validateAndConsume — valid token returns userId and atomically deletes the key")
    void validateAndConsume_validToken_returnsUserIdAndDeletes() {
        when(valueOps.getAndDelete(argThat(k -> k.startsWith("email-verify:")))).thenReturn("7");

        Long userId = emailVerificationService.validateAndConsume("any-valid-token");

        assertThat(userId).isEqualTo(7L);
        verify(valueOps).getAndDelete(argThat(k -> k.startsWith("email-verify:")));
    }

    @Test
    @DisplayName("validateAndConsume — expired or invalid token throws BAD_REQUEST with VERIFY_TOKEN_INVALID")
    void validateAndConsume_invalidToken_throws() {
        when(valueOps.getAndDelete(argThat(k -> k.startsWith("email-verify:")))).thenReturn(null);

        assertThatThrownBy(() -> emailVerificationService.validateAndConsume("bad-token"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(mbe.getErrorCode()).isEqualTo("VERIFY_TOKEN_INVALID");
                });
    }

    @Test
    @DisplayName("validateAndConsume — token consumed once cannot be replayed (atomic delete)")
    void validateAndConsume_sameTokenTwice_secondCallThrows() {
        when(valueOps.getAndDelete(argThat(k -> k.startsWith("email-verify:"))))
                .thenReturn("7")    // first call — link clicked
                .thenReturn(null);  // second call — link already used

        Long userId = emailVerificationService.validateAndConsume("one-time-token");
        assertThat(userId).isEqualTo(7L);

        assertThatThrownBy(() -> emailVerificationService.validateAndConsume("one-time-token"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
