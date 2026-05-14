package com.medibook.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics for health checks.
 * Tracks counters for each health check, consecutive failures, and last check time.
 */
@Slf4j
@Component
public class HealthMetrics {

    private final MeterRegistry meterRegistry;

    // Counters for each component
    private final Counter databaseCheckCount;
    private final Counter redisCheckCount;
    private final Counter kafkaCheckCount;
    private final Counter cassandraCheckCount;

    private final Counter databaseFailureCount;
    private final Counter redisFailureCount;
    private final Counter kafkaFailureCount;
    private final Counter cassandraFailureCount;

    // Track consecutive failures
    private final Map<String, AtomicInteger> consecutiveFailures;

    // Track last check time
    private final Map<String, Long> lastCheckTime;

    public HealthMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.consecutiveFailures = new ConcurrentHashMap<>();
        this.lastCheckTime = new ConcurrentHashMap<>();

        // Initialize counters for database
        this.databaseCheckCount = Counter.builder("health.check.total")
                .tag("component", "database")
                .description("Total database health checks")
                .register(meterRegistry);

        this.databaseFailureCount = Counter.builder("health.check.failure.total")
                .tag("component", "database")
                .description("Total database health check failures")
                .register(meterRegistry);

        // Initialize counters for Redis
        this.redisCheckCount = Counter.builder("health.check.total")
                .tag("component", "redis")
                .description("Total Redis health checks")
                .register(meterRegistry);

        this.redisFailureCount = Counter.builder("health.check.failure.total")
                .tag("component", "redis")
                .description("Total Redis health check failures")
                .register(meterRegistry);

        // Initialize counters for Kafka
        this.kafkaCheckCount = Counter.builder("health.check.total")
                .tag("component", "kafka")
                .description("Total Kafka health checks")
                .register(meterRegistry);

        this.kafkaFailureCount = Counter.builder("health.check.failure.total")
                .tag("component", "kafka")
                .description("Total Kafka health check failures")
                .register(meterRegistry);

        // Initialize counters for Cassandra
        this.cassandraCheckCount = Counter.builder("health.check.total")
                .tag("component", "cassandra")
                .description("Total Cassandra health checks")
                .register(meterRegistry);

        this.cassandraFailureCount = Counter.builder("health.check.failure.total")
                .tag("component", "cassandra")
                .description("Total Cassandra health check failures")
                .register(meterRegistry);

        // Initialize consecutive failure trackers
        consecutiveFailures.put("database", new AtomicInteger(0));
        consecutiveFailures.put("redis", new AtomicInteger(0));
        consecutiveFailures.put("kafka", new AtomicInteger(0));
        consecutiveFailures.put("cassandra", new AtomicInteger(0));

        // Initialize last check times
        long now = System.currentTimeMillis();
        lastCheckTime.put("database", now);
        lastCheckTime.put("redis", now);
        lastCheckTime.put("kafka", now);
        lastCheckTime.put("cassandra", now);
    }

    /**
     * Record a successful database health check.
     */
    public void recordDatabaseSuccess() {
        databaseCheckCount.increment();
        resetConsecutiveFailures("database");
        lastCheckTime.put("database", System.currentTimeMillis());
    }

    /**
     * Record a failed database health check.
     */
    public void recordDatabaseFailure() {
        databaseCheckCount.increment();
        databaseFailureCount.increment();
        int failures = incrementConsecutiveFailures("database");
        lastCheckTime.put("database", System.currentTimeMillis());

        if (failures >= 3) {
            log.warn("Database health check failed {} times consecutively. Consider restarting.", failures);
        }
    }

    /**
     * Record a successful Redis health check.
     */
    public void recordRedisSuccess() {
        redisCheckCount.increment();
        resetConsecutiveFailures("redis");
        lastCheckTime.put("redis", System.currentTimeMillis());
    }

    /**
     * Record a failed Redis health check.
     */
    public void recordRedisFailure() {
        redisCheckCount.increment();
        redisFailureCount.increment();
        int failures = incrementConsecutiveFailures("redis");
        lastCheckTime.put("redis", System.currentTimeMillis());

        if (failures >= 3) {
            log.warn("Redis health check failed {} times consecutively. Consider restarting.", failures);
        }
    }

    /**
     * Record a successful Kafka health check.
     */
    public void recordKafkaSuccess() {
        kafkaCheckCount.increment();
        resetConsecutiveFailures("kafka");
        lastCheckTime.put("kafka", System.currentTimeMillis());
    }

    /**
     * Record a failed Kafka health check.
     */
    public void recordKafkaFailure() {
        kafkaCheckCount.increment();
        kafkaFailureCount.increment();
        int failures = incrementConsecutiveFailures("kafka");
        lastCheckTime.put("kafka", System.currentTimeMillis());

        if (failures >= 3) {
            log.warn("Kafka health check failed {} times consecutively. Consider restarting.", failures);
        }
    }

    /**
     * Record a successful Cassandra health check.
     */
    public void recordCassandraSuccess() {
        cassandraCheckCount.increment();
        resetConsecutiveFailures("cassandra");
        lastCheckTime.put("cassandra", System.currentTimeMillis());
    }

    /**
     * Record a failed Cassandra health check.
     */
    public void recordCassandraFailure() {
        cassandraCheckCount.increment();
        cassandraFailureCount.increment();
        int failures = incrementConsecutiveFailures("cassandra");
        lastCheckTime.put("cassandra", System.currentTimeMillis());

        if (failures >= 3) {
            log.warn("Cassandra health check failed {} times consecutively. Consider restarting.", failures);
        }
    }

    /**
     * Get consecutive failure count for a component.
     */
    public int getConsecutiveFailures(String component) {
        return consecutiveFailures.getOrDefault(component, new AtomicInteger(0)).get();
    }

    /**
     * Get last check time for a component (in milliseconds since epoch).
     */
    public Long getLastCheckTime(String component) {
        return lastCheckTime.getOrDefault(component, System.currentTimeMillis());
    }

    /**
     * Increment consecutive failures counter.
     */
    private int incrementConsecutiveFailures(String component) {
        return consecutiveFailures.getOrDefault(component, new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Reset consecutive failures counter after a successful check.
     */
    private void resetConsecutiveFailures(String component) {
        consecutiveFailures.getOrDefault(component, new AtomicInteger(0)).set(0);
    }
}
