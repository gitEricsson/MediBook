package com.medibook.infrastructure.health;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * Health check for Cassandra via CqlSession.
 * Queries system.local table with 3s timeout.
 * Returns UNKNOWN if unavailable or timeout.
 */
@Slf4j
@Component
@ConditionalOnBean(CqlSession.class)
@RequiredArgsConstructor
public class CassandraHealthCheck {

    private static final int TIMEOUT_SECONDS = 3;
    private final CqlSession cqlSession;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CassandraHealthCheck");
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
            log.warn("Cassandra health check timed out after {}s", TIMEOUT_SECONDS);
            return HealthCheckResult.builder()
                    .status("UNKNOWN")
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Cassandra health check failed: {}", e.getMessage());
            return HealthCheckResult.builder()
                    .status("UNKNOWN")
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();
        }
    }

    /**
     * Execute the actual health check: SELECT from system.local.
     */
    private Boolean executeHealthCheck() {
        try {
            // Query system.local table to check cluster status
            ResultSet rs = cqlSession.execute(
                    cqlSession.prepare("SELECT * FROM system.local LIMIT 1")
                            .bind()
                            .setConsistencyLevel(com.datastax.oss.driver.api.core.ConsistencyLevel.LOCAL_ONE)
                            .setTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            );

            return rs.one() != null;

        } catch (Exception e) {
            log.debug("Cassandra system.local query failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected void finalize() {
        executor.shutdown();
    }
}
