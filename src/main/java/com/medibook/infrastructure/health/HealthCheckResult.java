package com.medibook.infrastructure.health;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Result of a health check for a dependency.
 */
@Getter
@Builder
public class HealthCheckResult {

    private String status;           // UP, DOWN, UNKNOWN
    private long responseTimeMs;     // Time taken for the check
    private Instant lastCheck;       // When the check was performed
    private Map<String, Object> details; // Provider-specific details

    /**
     * Is the dependency healthy (UP)?
     */
    public boolean isHealthy() {
        return "UP".equals(status);
    }
}
