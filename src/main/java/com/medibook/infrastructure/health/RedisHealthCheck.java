package com.medibook.infrastructure.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.*;

/**
 * Health check for Redis via Lettuce client.
 * PING command with 2s timeout.
 * Returns UNKNOWN if unreachable or timeout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthCheck {

    private static final int TIMEOUT_SECONDS = 2;
    private final RedisConnectionFactory connectionFactory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RedisHealthCheck");
        t.setDaemon(true);
        return t;
    });

    public HealthCheckResult check() {
        long startTime = System.currentTimeMillis();

        try {
            // Execute health check with timeout
            Future<Boolean> future = executor.submit(this::executeHealthCheck);
            boolean healthy = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long responseTime = System.currentTimeMillis() - startTime;
            String status = healthy ? "UP" : "DOWN";

            return HealthCheckResult.builder()
                    .status(status)
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();

        } catch (TimeoutException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Redis health check timed out after {}s", TIMEOUT_SECONDS);
            return HealthCheckResult.builder()
                    .status("UNKNOWN")
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Redis health check failed: {}", e.getMessage());
            return HealthCheckResult.builder()
                    .status("UNKNOWN")
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();
        }
    }

    /**
     * Execute the actual health check: PING.
     */
    private Boolean executeHealthCheck() {
        try {
            var connection = connectionFactory.getConnection();
            if (connection == null) {
                return false;
            }

            String response = connection.ping();
            connection.close();
            return "PONG".equals(response) || "OK".equals(response);

        } catch (Exception e) {
            log.debug("Redis PING failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected void finalize() {
        executor.shutdown();
    }
}
