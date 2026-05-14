package com.medibook.infrastructure.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * Health check for MySQL database via JDBC.
 * Executes: SELECT 1; with 5s timeout.
 * Returns UNKNOWN if timeout or error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseHealthCheck {

    private static final int TIMEOUT_SECONDS = 5;
    private final DataSource dataSource;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DatabaseHealthCheck");
        t.setDaemon(true);
        return t;
    });

    public HealthCheckResult check() {
        long startTime = System.currentTimeMillis();

        try {
            // Execute health check with timeout using virtual thread
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
            log.warn("Database health check timed out after {}s", TIMEOUT_SECONDS);
            return HealthCheckResult.builder()
                    .status("UNKNOWN")
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Database health check failed: {}", e.getMessage());
            return HealthCheckResult.builder()
                    .status("UNKNOWN")
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();
        }
    }

    /**
     * Execute the actual health check: SELECT 1.
     */
    private Boolean executeHealthCheck() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(TIMEOUT_SECONDS);
            stmt.executeQuery("SELECT 1");
            return true;
        } catch (Exception e) {
            log.debug("Database SELECT 1 failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected void finalize() {
        executor.shutdown();
    }
}
