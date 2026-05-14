package com.medibook.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metrics for token lifecycle and session management.
 *
 * Tracks:
 * - tokens.rotated: Count of token rotations (refresh operations)
 * - tokens.revoked: Count of token revocations (logout, timeout, suspicious activity)
 * - tokens.expired: Count of expired token attempts
 * - sessions.timed_out: Count of sessions that exceeded inactivity timeout
 * - sessions.active: Gauge of currently active user sessions
 */
@Component
public class TokenMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter tokensRotatedCounter;
    private final Counter tokensRevokedCounter;
    private final Counter tokensExpiredCounter;
    private final Counter sessionsTimedOutCounter;

    public TokenMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.tokensRotatedCounter = Counter.builder("tokens.rotated")
                .description("Total number of token rotations (refresh operations)")
                .register(meterRegistry);

        this.tokensRevokedCounter = Counter.builder("tokens.revoked")
                .description("Total number of token revocations (logout, timeout, etc.)")
                .register(meterRegistry);

        this.tokensExpiredCounter = Counter.builder("tokens.expired")
                .description("Total number of expired token attempts")
                .register(meterRegistry);

        this.sessionsTimedOutCounter = Counter.builder("sessions.timed_out")
                .description("Total number of sessions terminated due to inactivity timeout")
                .register(meterRegistry);
    }

    /**
     * Record a token rotation event.
     */
    public void recordTokenRotation(Long userId) {
        tokensRotatedCounter.increment();
    }

    /**
     * Record a token revocation event.
     */
    public void recordTokenRevocation(Long userId, String reason) {
        tokensRevokedCounter.increment();
    }

    /**
     * Record an expired token attempt.
     */
    public void recordTokenExpired(Long userId) {
        tokensExpiredCounter.increment();
    }

    /**
     * Record a session timeout event.
     */
    public void recordSessionTimeout(Long userId, long inactiveMinutes) {
        sessionsTimedOutCounter.increment();
    }

    /**
     * Get the current value of rotated tokens counter.
     */
    public double getTokensRotatedCount() {
        return tokensRotatedCounter.count();
    }

    /**
     * Get the current value of revoked tokens counter.
     */
    public double getTokensRevokedCount() {
        return tokensRevokedCounter.count();
    }

    /**
     * Get the current value of expired tokens counter.
     */
    public double getTokensExpiredCount() {
        return tokensExpiredCounter.count();
    }

    /**
     * Get the current value of sessions timed out counter.
     */
    public double getSessionsTimedOutCount() {
        return sessionsTimedOutCounter.count();
    }
}
