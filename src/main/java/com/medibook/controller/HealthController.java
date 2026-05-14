package com.medibook.controller;

import com.medibook.infrastructure.health.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Health Check Controller for Kubernetes and orchestration probes.
 *
 * - GET /health/live: Liveness probe (is the app running?)
 *   Returns 200 if app is running, 503 otherwise.
 *   Minimal checks, can be called frequently (10-30s intervals).
 *
 * - GET /health/ready: Readiness probe (can it handle traffic?)
 *   Returns 200 only if all dependencies are healthy.
 *   Slower, called less frequently (30-60s intervals).
 *   Checks: Database, Redis, Kafka, Cassandra.
 */
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final DatabaseHealthCheck databaseHealthCheck;
    private final RedisHealthCheck redisHealthCheck;
    private final KafkaHealthCheck kafkaHealthCheck;
    private final CassandraHealthCheck cassandraHealthCheck;

    /**
     * Liveness probe: Is the app running?
     *
     * Returns 200 if the app is running, minimal checks (no I/O).
     * Can be called frequently (10-30s intervals).
     *
     * @return 200 UP or 503 DOWN
     */
    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now());

        Map<String, String> checks = new HashMap<>();
        checks.put("runtime", "OK");
        response.put("checks", checks);

        return ResponseEntity.ok(response);
    }

    /**
     * Readiness probe: Can the app handle traffic?
     *
     * Returns 200 only if all dependencies are healthy.
     * Slower, called less frequently (30-60s intervals).
     * Checks: Database, Redis, Kafka, Cassandra.
     *
     * @return 200 UP if all dependencies healthy, 503 DOWN otherwise
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> components = new HashMap<>();

        // Check all dependencies
        HealthCheckResult database = databaseHealthCheck.check();
        components.put("database", buildComponentResponse(database));

        HealthCheckResult redis = redisHealthCheck.check();
        components.put("redis", buildComponentResponse(redis));

        HealthCheckResult kafka = kafkaHealthCheck.check();
        components.put("kafka", buildComponentResponse(kafka));

        HealthCheckResult cassandra = cassandraHealthCheck.check();
        components.put("cassandra", buildComponentResponse(cassandra));

        response.put("components", components);
        response.put("timestamp", Instant.now());

        // Determine overall status
        boolean allHealthy = database.isHealthy() && redis.isHealthy() &&
                           kafka.isHealthy() && cassandra.isHealthy();

        String status = allHealthy ? "UP" : "DOWN";
        response.put("status", status);

        HttpStatus httpStatus = allHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * Build component response from health check result.
     */
    private Map<String, Object> buildComponentResponse(HealthCheckResult result) {
        Map<String, Object> component = new HashMap<>();
        component.put("status", result.getStatus());

        Map<String, Object> details = new HashMap<>();
        details.put("response_time_ms", result.getResponseTimeMs());
        details.put("last_check", result.getLastCheck());

        // Add provider-specific details if available
        if (result.getDetails() != null && !result.getDetails().isEmpty()) {
            details.putAll(result.getDetails());
        }

        component.put("details", details);
        return component;
    }
}
