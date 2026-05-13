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
    private static final String HOLD_PREFIX = "appt_hold:";
    private static final Duration HOLD_DURATION = Duration.ofMinutes(10);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Attempts to place a temporary hold on a specific time slot for a doctor.
     * @return A unique holdId if successful.
     * @throws MediBookException if the slot is already held.
     */
    public String holdSlot(Long doctorId, LocalDateTime scheduledAt) {
        String slotKey = buildSlotKey(doctorId, scheduledAt);
        String holdId = UUID.randomUUID().toString();
        
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(slotKey, holdId, HOLD_DURATION);
        
        if (Boolean.TRUE.equals(acquired)) {
            log.info("Acquired hold [{}] for doctor {} at {}", holdId, doctorId, scheduledAt);
            return holdId;
        } else {
            log.warn("Failed to acquire hold. Slot {} already held.", slotKey);
            throw new MediBookException("This time slot is currently reserved by another user. Please select a different time.", 
                    HttpStatus.CONFLICT, "SLOT_TAKEN");
        }
    }

    /**
     * Validates that the provided holdId matches the active hold for the slot.
     */
    public void validateHold(Long doctorId, LocalDateTime scheduledAt, String providedHoldId) {
        if (providedHoldId == null || providedHoldId.isBlank()) {
            return; // If no holdId provided, we skip validation (rely on DB unique constraint)
        }
        
        String slotKey = buildSlotKey(doctorId, scheduledAt);
        String currentHoldId = redisTemplate.opsForValue().get(slotKey);
        
        if (currentHoldId == null) {
            throw new MediBookException("Hold has expired. Please try booking again.", HttpStatus.BAD_REQUEST, "HOLD_EXPIRED");
        }
        
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
        String currentHoldId = redisTemplate.opsForValue().get(slotKey);
        if (currentHoldId == null) {
            return;
        }
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
