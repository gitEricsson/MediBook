package com.medibook.config;

import com.medibook.audit.repository.AuditLogRepository;
import com.medibook.domain.notification.repository.NotificationRepository;
import com.medibook.domain.telemedicine.repository.CassandraChatMessageRepository;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.cassandra.core.CassandraOperations;

/**
 * Replaces the entire Cassandra infrastructure with no-op mocks so the
 * application context can start without a running Cassandra node.
 *
 * Three beans are required:
 *   1. AuditLogRepository   — injected into AuditLogService
 *   2. NotificationRepository — injected into NotificationService
 *   3. CassandraOperations  — directly injected into NotificationService
 *                             (NotificationService uses it for insert/query,
 *                              bypassing the repository for some operations)
 *
 * All three are absent when the three Cassandra auto-configurations are
 * excluded in application-test.yml, so this class is the sole provider.
 * No bean-name conflicts arise.
 *
 * Tests that need to control NotificationService behaviour (UserIntegrationTest)
 * add @MockBean NotificationService, which replaces the real service bean —
 * @MockBean always wins over a @Bean in the same context.
 */
@Configuration
@Profile("test")
public class TestCassandraConfig {

    @Bean
    public AuditLogRepository auditLogRepository() {
        return Mockito.mock(AuditLogRepository.class);
    }

    @Bean
    public NotificationRepository notificationRepository() {
        return Mockito.mock(NotificationRepository.class);
    }

    @Bean
    public CassandraChatMessageRepository cassandraChatMessageRepository() {
        return Mockito.mock(CassandraChatMessageRepository.class);
    }

    @Bean
    public CassandraOperations cassandraOperations() {
        return Mockito.mock(CassandraOperations.class);
    }
}
