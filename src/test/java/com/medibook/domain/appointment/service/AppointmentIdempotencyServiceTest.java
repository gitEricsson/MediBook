package com.medibook.domain.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.dto.AppointmentRequest;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentIdempotencyService - Unit Tests")
class AppointmentIdempotencyServiceTest {

    @Mock
    RedisTemplate<String, Object> redisTemplate;

    @Mock
    ValueOperations<String, Object> valueOperations;

    private AppointmentIdempotencyService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AppointmentIdempotencyService(redisTemplate, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("execute - bypasses Redis when key is absent")
    void execute_bypassesRedisWhenKeyMissing() {
        AtomicInteger calls = new AtomicInteger();
        AppointmentResponse expected = response();

        AppointmentResponse actual = service.execute(7L, null, request(), () -> {
            calls.incrementAndGet();
            return expected;
        });

        assertSame(expected, actual);
        assertEquals(1, calls.get());
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("execute - returns cached response for matching key and request")
    void execute_returnsCachedResponse() {
        AppointmentResponse expected = response();
        String cacheKey = "idempotency:appointments:7:abc-123";
        when(valueOperations.get(cacheKey)).thenReturn(
                java.util.Map.of(
                        "requestFingerprint", "9|2026-06-01T10:00|30|IN_PERSON|Follow up",
                        "response", expected));

        AppointmentResponse actual = service.execute(7L, "abc-123", request(), this::unexpectedAction);

        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getConfirmationCode(), actual.getConfirmationCode());
    }

    @Test
    @DisplayName("execute - rejects reused key for different request payload")
    void execute_rejectsReusedKeyForDifferentPayload() {
        String cacheKey = "idempotency:appointments:7:abc-123";
        when(valueOperations.get(cacheKey)).thenReturn(
                java.util.Map.of(
                        "requestFingerprint", "9|2026-06-01T10:00|30|IN_PERSON|Original",
                        "response", response()));

        AppointmentRequest differentRequest = request();
        differentRequest.setReason("Different reason");

        MediBookException ex = assertThrows(MediBookException.class,
                () -> service.execute(7L, "abc-123", differentRequest, this::unexpectedAction));

        assertEquals("IDEMPOTENCY_KEY_REUSED", ex.getErrorCode());
    }

    private AppointmentRequest request() {
        AppointmentRequest request = new AppointmentRequest();
        request.setDoctorId(9L);
        request.setScheduledAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        request.setDurationMins(30);
        request.setType(AppointmentType.IN_PERSON);
        request.setReason("Follow up");
        return request;
    }

    private AppointmentResponse response() {
        return AppointmentResponse.builder()
                .id(42L)
                .patientId(7L)
                .patientName("James Patient")
                .doctorId(9L)
                .doctorName("Dr. Ada")
                .departmentName("Cardiology")
                .scheduledAt(LocalDateTime.of(2026, 6, 1, 10, 0))
                .durationMins(30)
                .status(AppointmentStatus.PENDING)
                .type(AppointmentType.IN_PERSON)
                .confirmationCode("MB-ABC123")
                .createdAt(LocalDateTime.of(2026, 5, 1, 9, 0))
                .build();
    }

    private AppointmentResponse unexpectedAction() {
        throw new AssertionError("booking action should not be invoked");
    }
}
