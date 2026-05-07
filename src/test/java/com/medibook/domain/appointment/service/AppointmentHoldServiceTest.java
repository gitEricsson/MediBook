package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentHoldService — Unit Tests")
class AppointmentHoldServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks AppointmentHoldService holdService;

    private static final Long   DOCTOR_ID = 10L;
    private static final LocalDateTime SLOT = LocalDateTime.of(2026, 6, 20, 10, 0);

    private static String keyStartsWith(String prefix) {
        return argThat((String k) -> k != null && k.startsWith(prefix));
    }

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }


    @Test
    @DisplayName("holdSlot — SETNX succeeds, returns a non-blank UUID holdId")
    void holdSlot_setnxSucceeds_returnsHoldId() {
        when(valueOps.setIfAbsent(keyStartsWith("appt_hold:"), anyString(), any(Duration.class)))
                .thenReturn(true);

        String holdId = holdService.holdSlot(DOCTOR_ID, SLOT);

        assertThat(holdId).isNotBlank();
    }

    @Test
    @DisplayName("holdSlot — key is stored under appt_hold: prefix with 10-minute TTL")
    void holdSlot_usesCorrectPrefixAndTenMinuteTtl() {
        when(valueOps.setIfAbsent(any(), anyString(), any())).thenReturn(true);

        holdService.holdSlot(DOCTOR_ID, SLOT);

        verify(valueOps).setIfAbsent(
                keyStartsWith("appt_hold:"),
                anyString(),
                eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("holdSlot — SETNX fails (slot already held) throws 409 SLOT_TAKEN")
    void holdSlot_slotAlreadyHeld_throwsSlotTaken() {
        when(valueOps.setIfAbsent(any(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> holdService.holdSlot(DOCTOR_ID, SLOT))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(mbe.getErrorCode()).isEqualTo("SLOT_TAKEN");
                });
    }

    @Test
    @DisplayName("holdSlot — each invocation generates a unique holdId")
    void holdSlot_generatesUniqueHoldIds() {
        when(valueOps.setIfAbsent(any(), anyString(), any())).thenReturn(true);

        String first  = holdService.holdSlot(DOCTOR_ID, SLOT);
        String second = holdService.holdSlot(DOCTOR_ID, SLOT);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("holdSlot — key encodes both doctorId and ISO slot time")
    void holdSlot_keyEncodesDoctoridAndSlotTime() {
        when(valueOps.setIfAbsent(any(), anyString(), any())).thenReturn(true);

        holdService.holdSlot(DOCTOR_ID, SLOT);

        verify(valueOps).setIfAbsent(
                argThat(k -> k != null
                        && k.contains(String.valueOf(DOCTOR_ID))
                        && k.contains("2026-06-20T10:00:00")),
                anyString(),
                any());
    }


    @Test
    @DisplayName("validateHold — null holdId skips validation, no Redis interaction")
    void validateHold_nullHoldId_skipsValidation() {
        holdService.validateHold(DOCTOR_ID, SLOT, null);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("validateHold — blank holdId skips validation, no Redis interaction")
    void validateHold_blankHoldId_skipsValidation() {
        holdService.validateHold(DOCTOR_ID, SLOT, "   ");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("validateHold — matching holdId passes without throwing")
    void validateHold_matchingHoldId_passes() {
        when(valueOps.get(keyStartsWith("appt_hold:"))).thenReturn("correct-hold-id");

        assertThatCode(() -> holdService.validateHold(DOCTOR_ID, SLOT, "correct-hold-id"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateHold — Redis key missing (expired) throws 400 HOLD_EXPIRED")
    void validateHold_expiredHold_throwsHoldExpired() {
        when(valueOps.get(keyStartsWith("appt_hold:"))).thenReturn(null);

        assertThatThrownBy(() -> holdService.validateHold(DOCTOR_ID, SLOT, "stale-hold"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(mbe.getErrorCode()).isEqualTo("HOLD_EXPIRED");
                });
    }

    @Test
    @DisplayName("validateHold — holdId mismatch throws 403 INVALID_HOLD")
    void validateHold_mismatchedHoldId_throwsInvalidHold() {
        when(valueOps.get(keyStartsWith("appt_hold:"))).thenReturn("real-hold");

        assertThatThrownBy(() -> holdService.validateHold(DOCTOR_ID, SLOT, "wrong-hold"))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    MediBookException mbe = (MediBookException) ex;
                    assertThat(mbe.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(mbe.getErrorCode()).isEqualTo("INVALID_HOLD");
                });
    }


    @Test
    @DisplayName("releaseHold — deletes the appt_hold: key from Redis")
    void releaseHold_deletesSlotKey() {
        holdService.releaseHold(DOCTOR_ID, SLOT);

        verify(redisTemplate).delete(keyStartsWith("appt_hold:"));
    }

    @Test
    @DisplayName("releaseHold — deleted key contains doctorId and ISO slot time")
    void releaseHold_keyEncodesDoctoridAndSlotTime() {
        holdService.releaseHold(DOCTOR_ID, SLOT);

        verify(redisTemplate).delete(argThat((String k) -> k != null
                && k.contains(String.valueOf(DOCTOR_ID))
                && k.contains("2026-06-20T10:00:00")));
    }

    @Test
    @DisplayName("releaseHold — different doctors/slots produce different keys")
    void releaseHold_keysAreIsolatedPerDoctorAndSlot() {
        LocalDateTime otherSlot = SLOT.plusHours(1);

        holdService.releaseHold(DOCTOR_ID, SLOT);
        holdService.releaseHold(DOCTOR_ID, otherSlot);

        verify(redisTemplate).delete(argThat((String k) -> k != null && k.contains("10:00:00")));
        verify(redisTemplate).delete(argThat((String k) -> k != null && k.contains("11:00:00")));
    }
}
