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
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
    @DisplayName("validate — valid token returns userId without deleting")
    void validate_validToken_returnsUserId() {
        when(valueOps.get(argThat((String k) -> k.startsWith("email-verify:")))).thenReturn("7");

        Long userId = emailVerificationService.validate("any-valid-token");

        assertThat(userId).isEqualTo(7L);
        verify(redisTemplate, never()).delete((String) any());
    }

    @Test
    @DisplayName("validate — expired or invalid token throws BAD_REQUEST with VERIFY_TOKEN_INVALID")
    void validate_invalidToken_throws() {
        when(valueOps.get(argThat((String k) -> k.startsWith("email-verify:")))).thenReturn(null);

        assertThatThrownBy(() -> emailVerificationService.validate("bad-token"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(mbe.getErrorCode()).isEqualTo("VERIFY_TOKEN_INVALID");
                });
    }

    @Test
    @DisplayName("validate — token can be read multiple times before consume")
    void validate_sameTokenTwice_bothSucceed() {
        when(valueOps.get(argThat((String k) -> k.startsWith("email-verify:")))).thenReturn("7");

        assertThat(emailVerificationService.validate("reusable-token")).isEqualTo(7L);
        assertThat(emailVerificationService.validate("reusable-token")).isEqualTo(7L);
    }

    @Test
    @DisplayName("consume — deletes the token from Redis")
    void consume_deletesToken() {
        emailVerificationService.consume("spent-token");

        verify(redisTemplate).delete("email-verify:spent-token");
    }
}
