package com.medibook.infrastructure.health;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Health check for Kafka via AdminClient.
 * Checks if Kafka broker is reachable via AdminClient.listTopics().
 * 5s timeout.
 * Returns UNKNOWN if connection fails or timeout.
 */
@Slf4j
@Component
public class KafkaHealthCheck {

    private static final int TIMEOUT_SECONDS = 5;
    private final String bootstrapServers;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KafkaHealthCheck");
        t.setDaemon(true);
        return t;
    });

    public KafkaHealthCheck(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public HealthCheckResult check() {
        long startTime = System.currentTimeMillis();

        try {
            // Execute health check with timeout
            Future<Integer> future = executor.submit(this::executeHealthCheck);
            Integer brokersAvailable = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long responseTime = System.currentTimeMillis() - startTime;
            boolean healthy = brokersAvailable != null && brokersAvailable > 0;
            String status = healthy ? "UP" : "DOWN";

            Map<String, Object> details = new HashMap<>();
            if (brokersAvailable != null) {
                details.put("brokers_available", brokersAvailable);
            }

            return HealthCheckResult.builder()
                    .status(status)
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .details(details)
                    .build();

        } catch (TimeoutException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Kafka health check timed out after {}s", TIMEOUT_SECONDS);
            return HealthCheckResult.builder()
                    .status("UNKNOWN")
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Kafka health check failed: {}", e.getMessage());
            return HealthCheckResult.builder()
                    .status("UNKNOWN")
                    .responseTimeMs(responseTime)
                    .lastCheck(Instant.now())
                    .build();
        }
    }

    /**
     * Execute the actual health check: listTopics to get broker count.
     */
    private Integer executeHealthCheck() {
        AdminClient adminClient = null;
        try {
            Map<String, Object> config = new HashMap<>();
            config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_SECONDS * 1000);

            adminClient = AdminClient.create(config);

            ListTopicsResult result = adminClient.listTopics();
            // Block with timeout
            result.names().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Count available brokers from cluster metadata
            var cluster = adminClient.describeCluster();
            var brokers = cluster.nodes().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return brokers.size();

        } catch (TimeoutException e) {
            log.debug("Kafka listTopics timed out");
            throw new RuntimeException("Kafka timeout", e);

        } catch (Exception e) {
            log.debug("Kafka health check failed: {}", e.getMessage());
            throw new RuntimeException("Kafka check failed", e);

        } finally {
            if (adminClient != null) {
                try {
                    adminClient.close(Duration.ofSeconds(1));
                } catch (Exception e) {
                    log.debug("Failed to close Kafka AdminClient: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    protected void finalize() {
        executor.shutdown();
    }
}
