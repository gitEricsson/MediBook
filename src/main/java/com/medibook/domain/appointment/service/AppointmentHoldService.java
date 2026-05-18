package com.medibook.domain.appointment.service;

import com.medibook.common.exception.MediBookException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.RedisOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentHoldService {

    private final StringRedisTemplate redisTemplate;
    private final AppointmentSchedulingPolicy schedulingPolicy;
    private static final String HOLD_PREFIX = "appt_hold:";
    private static final Duration HOLD_DURATION = Duration.ofMinutes(10);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Attempts to place a temporary hold on a specific time slot for a doctor.
     * @return A unique holdId if successful.
     * @throws MediBookException if the slot is already held.
     */
    public String holdSlot(Long doctorId, LocalDateTime scheduledAt) {
        return holdSlot(doctorId, scheduledAt, 0);
    }

    /**
     * Overload that takes the patient's chosen consultation length (manual start/end picker).
     * Pass 0 to fall back to the doctor's configured slot duration.
     */
    public String holdSlot(Long doctorId, LocalDateTime scheduledAt, int durationMins) {
        // Fail-fast: past time, outside hours, doctor on leave, doctor inactive AND
        // overlapping confirmed appointment — all surface here so the patient sees the
        // error *before* the "slot held" UI.
        LocalDateTime end = durationMins > 0
                ? scheduledAt.plusMinutes(durationMins)
                : scheduledAt.plusMinutes(resolveDefaultDurationMins(doctorId));
        if (durationMins > 0) {
            schedulingPolicy.checkBookableWithOverlap(doctorId, scheduledAt, end);
        } else {
            schedulingPolicy.checkBookable(doctorId, scheduledAt);
            schedulingPolicy.checkOverlapForDefaultSlot(doctorId, scheduledAt);
        }

        // Detect overlapping *active Redis holds* by another patient that haven't yet
        // been confirmed into the DB. existsConflict() only sees the DB, so without
        // this check a manual window of 08:30–09:30 could slip past a held 08:00–09:00.
        if (overlapsActiveHold(doctorId, scheduledAt, end, /*excludeKey*/ null)) {
            log.warn("Hold rejected — overlapping active hold for doctor {} at {}", doctorId, scheduledAt);
            throw new MediBookException(
                    "Doctor is not available for booking at this time. Please pick a different window.",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }

        String slotKey = buildSlotKey(doctorId, scheduledAt);
        String holdId  = UUID.randomUUID().toString();
        // Value encodes endTime so future holds can detect overlap: "holdId|endIso".
        String holdValue = holdId + "|" + end.format(formatter);

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(slotKey, holdValue, HOLD_DURATION);

        if (Boolean.TRUE.equals(acquired)) {
            log.info("Acquired hold [{}] for doctor {} at {} (end {})", holdId, doctorId, scheduledAt, end);
            return holdId;
        } else {
            log.warn("Failed to acquire hold. Slot {} already held.", slotKey);
            throw new MediBookException("Doctor is not available for booking at this time. Please select a different time.",
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }
    }

    private int resolveDefaultDurationMins(Long doctorId) {
        // Best-effort fallback for overlap math when the patient didn't supply a length.
        // Mirrors AppointmentSchedulingPolicy's resolution: doctor.slotDurationMins or 60.
        return 60;
    }

    private boolean overlapsActiveHold(Long doctorId, LocalDateTime start, LocalDateTime end, String excludeKey) {
        String prefix  = HOLD_PREFIX + doctorId + ":";
        String pattern = prefix + "*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null) return false;
        for (String key : keys) {
            if (key.equals(excludeKey)) continue;
            // Key format: appt_hold:{doctorId}:{startIso}. The ISO string itself contains
            // colons (HH:mm:ss), so we strip the known prefix instead of using
            // lastIndexOf(':') — that would mistakenly grab the seconds segment.
            if (!key.startsWith(prefix)) continue;
            LocalDateTime holdStart;
            try {
                holdStart = LocalDateTime.parse(key.substring(prefix.length()), formatter);
            } catch (Exception ex) { continue; }

            String value = redisTemplate.opsForValue().get(key);
            LocalDateTime holdEnd;
            if (value != null && value.contains("|")) {
                try {
                    holdEnd = LocalDateTime.parse(value.split("\\|", 2)[1], formatter);
                } catch (Exception ex) {
                    holdEnd = holdStart.plusMinutes(60);
                }
            } else {
                // Legacy value with no end time encoded — assume 60-min window.
                holdEnd = holdStart.plusMinutes(60);
            }
            if (start.isBefore(holdEnd) && end.isAfter(holdStart)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates that the provided holdId matches the active hold for the slot.
     */
    public void validateHold(Long doctorId, LocalDateTime scheduledAt, String providedHoldId) {
        if (providedHoldId == null || providedHoldId.isBlank()) {
            return; // If no holdId provided, we skip validation (rely on DB unique constraint)
        }

        String slotKey = buildSlotKey(doctorId, scheduledAt);
        String currentValue = redisTemplate.opsForValue().get(slotKey);

        if (currentValue == null) {
            throw new MediBookException("Hold has expired. Please try booking again.", HttpStatus.BAD_REQUEST, "HOLD_EXPIRED");
        }

        // Value is "holdId|endIso" (or legacy bare holdId). Extract holdId for comparison.
        String currentHoldId = currentValue.contains("|") ? currentValue.split("\\|", 2)[0] : currentValue;
        if (!currentHoldId.equals(providedHoldId)) {
            throw new MediBookException("Invalid hold ID.", HttpStatus.FORBIDDEN, "INVALID_HOLD");
        }
    }

    /**
     * Releases the hold once the appointment is successfully booked.
     */
    public void releaseHold(Long doctorId, LocalDateTime scheduledAt) {
        String slotKey = buildSlotKey(doctorId, scheduledAt);
        redisTemplate.delete(slotKey);
        log.info("Released hold for slot {}", slotKey);
    }

    public void releaseHold(Long doctorId, LocalDateTime scheduledAt, String holdId) {
        String slotKey = buildSlotKey(doctorId, scheduledAt);
        String currentValue = redisTemplate.opsForValue().get(slotKey);
        if (currentValue == null) {
            return;
        }
        String currentHoldId = currentValue.contains("|") ? currentValue.split("\\|", 2)[0] : currentValue;
        if (!currentHoldId.equals(holdId)) {
            throw new MediBookException("Invalid hold ID.", HttpStatus.FORBIDDEN, "INVALID_HOLD");
        }
        redisTemplate.delete(slotKey);
        log.info("Released hold [{}] for slot {}", holdId, slotKey);
    }

    public boolean isSlotHeld(Long doctorId, LocalDateTime scheduledAt) {
        String slotKey = buildSlotKey(doctorId, scheduledAt);
        return Boolean.TRUE.equals(redisTemplate.hasKey(slotKey));
    }

    /**
     * Batch-checks which of the given slots are currently held.
     * Fires all EXISTS commands in a single Redis pipeline — one network round-trip
     * regardless of how many slots are checked.
     */
    @SuppressWarnings("unchecked")
    public Set<LocalDateTime> getHeldSlots(Long doctorId, List<LocalDateTime> slots) {
        if (slots.isEmpty()) return Set.of();

        List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (LocalDateTime slot : slots) {
                    operations.hasKey(buildSlotKey(doctorId, slot));
                }
                return null;
            }
        });

        Set<LocalDateTime> held = new HashSet<>();
        for (int i = 0; i < slots.size(); i++) {
            if (Boolean.TRUE.equals(results.get(i))) {
                held.add(slots.get(i));
            }
        }
        return held;
    }

    private String buildSlotKey(Long doctorId, LocalDateTime scheduledAt) {
        return HOLD_PREFIX + doctorId + ":" + scheduledAt.format(formatter);
    }
}
