package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.doctor.repository.DoctorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency-sensitive — every regression here either double-books a slot or
 * silently drops a valid one. Covers hold-overlap detection (Redis scan),
 * legacy-format value parsing, and validate/release contracts.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("AppointmentHoldService — Unit Tests")
class AppointmentHoldServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock AppointmentSchedulingPolicy schedulingPolicy;
    @Mock DoctorRepository doctorRepository;
    @InjectMocks AppointmentHoldService holdService;

    private static final long DOCTOR_ID = 1L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 5, 18, 10, 0);

    @BeforeEach
    void wireRedis() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested @DisplayName("holdSlot — happy path")
    class HappyPath {
        @Test void acquiresHold_andStoresEndTime() {
            doNothing().when(schedulingPolicy).checkBookableWithOverlap(eq(DOCTOR_ID), eq(START), any());
            when(redisTemplate.keys(anyString())).thenReturn(Set.of());
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

            String id = holdService.holdSlot(DOCTOR_ID, START, 30);

            assertThat(id).isNotBlank();
            verify(valueOps).setIfAbsent(
                    eq("appt_hold:1:2026-05-18T10:00:00"),
                    argThat((String v) -> v.contains("|") && v.endsWith("10:30:00")),
                    eq(Duration.ofMinutes(10)));
        }
    }

    @Nested @DisplayName("holdSlot — overlap with active Redis holds")
    class OverlapWithRedisHolds {
        @Test void rejectsRequestThatOverlapsHeldWindow() {
            doNothing().when(schedulingPolicy).checkBookableWithOverlap(anyLong(), any(), any());
            String existingKey = "appt_hold:1:2026-05-18T10:00:00";
            when(redisTemplate.keys("appt_hold:1:*")).thenReturn(Set.of(existingKey));
            when(valueOps.get(existingKey)).thenReturn("aaaa-aaaa|2026-05-18T11:00:00");

            assertThatThrownBy(() -> holdService.holdSlot(DOCTOR_ID, START.plusMinutes(30), 60))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("not available");
        }

        @Test void acceptsRequestThatJustTouchesShiftBoundary() {
            doNothing().when(schedulingPolicy).checkBookableWithOverlap(anyLong(), any(), any());
            String existingKey = "appt_hold:1:2026-05-18T10:00:00";
            when(redisTemplate.keys("appt_hold:1:*")).thenReturn(Set.of(existingKey));
            when(valueOps.get(existingKey)).thenReturn("aaaa-aaaa|2026-05-18T10:30:00");
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

            String id = holdService.holdSlot(DOCTOR_ID, START.plusMinutes(30), 30);
            assertThat(id).isNotBlank();
        }

        @Test void parsesLegacyValueWithoutEndTime_assumes60MinWindow() {
            // Legacy holds (pre-fix) stored just the holdId, no "|endIso". Assume 60 min.
            doNothing().when(schedulingPolicy).checkBookableWithOverlap(anyLong(), any(), any());
            String existingKey = "appt_hold:1:2026-05-18T10:00:00";
            when(redisTemplate.keys("appt_hold:1:*")).thenReturn(Set.of(existingKey));
            when(valueOps.get(existingKey)).thenReturn("bare-holdid-no-end");
            assertThatThrownBy(() -> holdService.holdSlot(DOCTOR_ID, START.plusMinutes(30), 30))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("not available");
        }
    }

    @Nested @DisplayName("holdSlot — race on identical slot key")
    class IdenticalKeyRace {
        @Test void rejectsSecondHolderOnSameStart() {
            doNothing().when(schedulingPolicy).checkBookableWithOverlap(anyLong(), any(), any());
            when(redisTemplate.keys(anyString())).thenReturn(Set.of());
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

            assertThatThrownBy(() -> holdService.holdSlot(DOCTOR_ID, START, 30))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("not available");
        }
    }

    @Nested @DisplayName("holdSlot — grid path (durationMins=0)")
    class GridPath {
        @Test void usesDefaultSlotPolicyWhenNoDurationProvided() {
            doNothing().when(schedulingPolicy).checkBookable(eq(DOCTOR_ID), eq(START));
            doNothing().when(schedulingPolicy).checkOverlapForDefaultSlot(eq(DOCTOR_ID), eq(START));
            when(redisTemplate.keys(anyString())).thenReturn(Set.of());
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

            String id = holdService.holdSlot(DOCTOR_ID, START, 0);
            assertThat(id).isNotBlank();
            verify(schedulingPolicy).checkBookable(DOCTOR_ID, START);
            verify(schedulingPolicy).checkOverlapForDefaultSlot(DOCTOR_ID, START);
        }
    }

    @Nested @DisplayName("validateHold")
    class ValidateHold {
        @Test void skipsValidationWhenHoldIdIsBlank() {
            holdService.validateHold(DOCTOR_ID, START, "");
            holdService.validateHold(DOCTOR_ID, START, null);
            verify(valueOps, never()).get(anyString());
        }
        @Test void rejectsWhenHoldExpired() {
            when(valueOps.get(anyString())).thenReturn(null);
            assertThatThrownBy(() -> holdService.validateHold(DOCTOR_ID, START, "any-id"))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("expired");
        }
        @Test void rejectsWrongHoldId() {
            when(valueOps.get(anyString())).thenReturn("real-holdid|2026-05-18T10:30:00");
            assertThatThrownBy(() -> holdService.validateHold(DOCTOR_ID, START, "wrong-id"))
                    .isInstanceOf(MediBookException.class)
                    .hasMessageContaining("Invalid hold");
        }
        @Test void acceptsMatchingHoldIdInNewFormat() {
            when(valueOps.get(anyString())).thenReturn("real-holdid|2026-05-18T10:30:00");
            holdService.validateHold(DOCTOR_ID, START, "real-holdid");
        }
        @Test void acceptsMatchingHoldIdInLegacyFormat() {
            when(valueOps.get(anyString())).thenReturn("legacy-bare-id");
            holdService.validateHold(DOCTOR_ID, START, "legacy-bare-id");
        }
    }

    @Nested @DisplayName("releaseHold")
    class ReleaseHold {
        @Test void releasesMatchingHold() {
            when(valueOps.get(anyString())).thenReturn("ok-id|2026-05-18T10:30:00");
            holdService.releaseHold(DOCTOR_ID, START, "ok-id");
            verify(redisTemplate).delete(anyString());
        }
        @Test void noopWhenAlreadyGone() {
            when(valueOps.get(anyString())).thenReturn(null);
            holdService.releaseHold(DOCTOR_ID, START, "ok-id");
            verify(redisTemplate, never()).delete(anyString());
        }
        @Test void rejectsWrongHoldId() {
            when(valueOps.get(anyString())).thenReturn("real-id|2026-05-18T10:30:00");
            assertThatThrownBy(() -> holdService.releaseHold(DOCTOR_ID, START, "wrong"))
                    .isInstanceOf(MediBookException.class);
        }
    }
}
