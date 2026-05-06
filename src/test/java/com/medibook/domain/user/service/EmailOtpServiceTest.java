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
@DisplayName("EmailOtpService — Unit Tests")
class EmailOtpServiceTest {
    private static String keyStartsWith(String prefix) {
        // null guard: argThat passes null while Mockito resolves stub ordering
        return argThat((String k) -> k != null && k.startsWith(prefix));
    }

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Mock JavaMailSender mailSender;

    @InjectMocks EmailOtpService emailOtpService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ─── generateAndStore ────────────────────────────────────────────────────

    @Test
    @DisplayName("generateAndStore — first request returns a 6-digit OTP and stores it in Redis")
    void generateAndStore_firstRequest_storesOtpInRedis() {
        when(valueOps.increment(keyStartsWith("otp:gen:"))).thenReturn(1L);

        String otp = emailOtpService.generateAndStore("user@medibook.com");

        assertThat(otp).hasSize(6).matches("\\d{6}");
        verify(valueOps).set(
                keyStartsWith("otp:code:"),
                eq(otp),
                any(Duration.class));
    }

    @Test
    @DisplayName("generateAndStore — first increment sets expiry on the generation-count key")
    void generateAndStore_firstIncrement_setsTtlOnGenKey() {
        when(valueOps.increment(keyStartsWith("otp:gen:"))).thenReturn(1L);

        emailOtpService.generateAndStore("user@medibook.com");

        verify(redisTemplate).expire(keyStartsWith("otp:gen:"), any(Duration.class));
    }

    @Test
    @DisplayName("generateAndStore — subsequent requests within window do not reset expiry")
    void generateAndStore_subsequentRequest_doesNotResetGenTtl() {
        when(valueOps.increment(keyStartsWith("otp:gen:"))).thenReturn(2L);

        emailOtpService.generateAndStore("user@medibook.com");

        verify(redisTemplate, never()).expire(keyStartsWith("otp:gen:"), any());
    }

    @Test
    @DisplayName("generateAndStore — exceeding 3 requests per window throws TOO_MANY_REQUESTS")
    void generateAndStore_rateLimitExceeded_throws() {
        when(valueOps.increment(keyStartsWith("otp:gen:"))).thenReturn(4L);

        assertThatThrownBy(() -> emailOtpService.generateAndStore("user@medibook.com"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(mbe.getErrorCode()).isEqualTo("OTP_RATE_LIMITED");
                });
        verify(valueOps, never()).set(keyStartsWith("otp:code:"), any(), any());
    }

    // ─── verify ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify — correct OTP deletes both the code key and the fail counter")
    void verify_validOtp_cleansUpBothRedisKeys() {
        when(valueOps.get(keyStartsWith("otp:fail:"))).thenReturn(null);
        when(valueOps.get(keyStartsWith("otp:code:"))).thenReturn("654321");

        emailOtpService.verify("user@medibook.com", "654321");

        verify(redisTemplate).delete(keyStartsWith("otp:code:"));
        verify(redisTemplate).delete(keyStartsWith("otp:fail:"));
    }

    @Test
    @DisplayName("verify — expired or missing OTP throws UNAUTHORIZED without incrementing fails")
    void verify_expiredOtp_throwsOtpExpired() {
        when(valueOps.get(keyStartsWith("otp:fail:"))).thenReturn(null);
        when(valueOps.get(keyStartsWith("otp:code:"))).thenReturn(null);

        assertThatThrownBy(() -> emailOtpService.verify("user@medibook.com", "123456"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(mbe.getErrorCode()).isEqualTo("OTP_EXPIRED");
                });
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    @DisplayName("verify — wrong OTP increments the fail counter and throws UNAUTHORIZED")
    void verify_wrongOtp_incrementsFailsAndThrows() {
        when(valueOps.get(keyStartsWith("otp:fail:"))).thenReturn(null);
        when(valueOps.get(keyStartsWith("otp:code:"))).thenReturn("654321");
        when(valueOps.increment(keyStartsWith("otp:fail:"))).thenReturn(1L);

        assertThatThrownBy(() -> emailOtpService.verify("user@medibook.com", "000000"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(mbe.getErrorCode()).isEqualTo("OTP_INVALID");
                });
        verify(valueOps).increment(keyStartsWith("otp:fail:"));
    }

    @Test
    @DisplayName("verify — first wrong attempt sets lockout TTL on the fail key")
    void verify_firstWrongAttempt_setsLockoutTtl() {
        when(valueOps.get(keyStartsWith("otp:fail:"))).thenReturn(null);
        when(valueOps.get(keyStartsWith("otp:code:"))).thenReturn("654321");
        when(valueOps.increment(keyStartsWith("otp:fail:"))).thenReturn(1L);

        assertThatThrownBy(() -> emailOtpService.verify("user@medibook.com", "000000"))
                .isInstanceOf(MediBookException.class);

        verify(redisTemplate).expire(keyStartsWith("otp:fail:"), any(Duration.class));
    }

    @Test
    @DisplayName("verify — subsequent wrong attempts do not reset the lockout TTL")
    void verify_subsequentWrongAttempt_doesNotResetLockoutTtl() {
        when(valueOps.get(keyStartsWith("otp:fail:"))).thenReturn(null);
        when(valueOps.get(keyStartsWith("otp:code:"))).thenReturn("654321");
        when(valueOps.increment(keyStartsWith("otp:fail:"))).thenReturn(3L);  // not first fail

        assertThatThrownBy(() -> emailOtpService.verify("user@medibook.com", "000000"))
                .isInstanceOf(MediBookException.class);

        verify(redisTemplate, never()).expire(keyStartsWith("otp:fail:"), any());
    }

    @Test
    @DisplayName("verify — account locked at 5 failed attempts throws TOO_MANY_REQUESTS without checking code")
    void verify_accountLocked_throwsWithoutCodeLookup() {
        when(valueOps.get(keyStartsWith("otp:fail:"))).thenReturn("5");

        assertThatThrownBy(() -> emailOtpService.verify("user@medibook.com", "123456"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(mbe.getErrorCode()).isEqualTo("OTP_LOCKED");
                });
        verify(valueOps, never()).get(keyStartsWith("otp:code:"));
    }
}
