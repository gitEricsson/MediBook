package com.medibook.config;

import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.medibook.audit.repository.AuditLogRepository;
import com.medibook.domain.notification.repository.NotificationRepository;
import com.medibook.domain.telemedicine.repository.CallParticipantRepository;
import com.medibook.domain.telemedicine.repository.CassandraChatMessageRepository;
import com.medibook.domain.telemedicine.repository.TelemedicineChatMessageRepository;
import com.medibook.domain.telemedicine.repository.TelemedicineSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.cassandra.config.AbstractCassandraConfiguration;
import org.springframework.data.cassandra.config.DriverConfigLoaderBuilderConfigurer;
import org.springframework.data.cassandra.config.SchemaAction;
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories;

import java.time.Duration;

@Configuration
@Profile("!test")
@EnableCassandraRepositories(
        basePackageClasses = {
                AuditLogRepository.class,
                NotificationRepository.class,
                CassandraChatMessageRepository.class
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        TelemedicineChatMessageRepository.class,
                        TelemedicineSessionRepository.class,
                        CallParticipantRepository.class
                }
        )
)
public class CassandraConfig extends AbstractCassandraConfiguration {

    @Value("${spring.data.cassandra.keyspace-name}")
    private String keyspaceName;

    @Value("${spring.data.cassandra.contact-points}")
    private String contactPoints;

    @Value("${spring.data.cassandra.port}")
    private int port;

    @Value("${spring.data.cassandra.local-datacenter}")
    private String localDatacenter;

    @Value("${spring.cassandra.request.timeout:15s}")
    private Duration requestTimeout;

    @Override
    protected String getKeyspaceName() {
        return keyspaceName;
    }

    @Override
    protected String getContactPoints() {
        return contactPoints;
    }

    @Override
    protected int getPort() {
        return port;
    }

    @Override
    protected String getLocalDataCenter() {
        return localDatacenter;
    }

    @Override
    public SchemaAction getSchemaAction() {
        return SchemaAction.CREATE_IF_NOT_EXISTS;
    }

    @Override
    protected DriverConfigLoaderBuilderConfigurer getDriverConfigLoaderBuilderConfigurer() {
        // Fallback guards against @Value injection failures (null Duration from bad parse)
        Duration timeout = (requestTimeout != null) ? requestTimeout : Duration.ofSeconds(15);
        return builder -> builder
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, timeout)
                // When running outside Docker the contact point is localhost:9042 (port-mapped).
                // Without this, the driver swaps localhost for Cassandra's Docker-internal IP
                // (e.g. 172.18.x.x) after peer discovery, which is unreachable from the host.
                .withBoolean(DefaultDriverOption.RESOLVE_CONTACT_POINTS, false);
    }

    @Override
    public String[] getEntityBasePackages() {
        return new String[] {
                "com.medibook.audit.entity",
                "com.medibook.domain.notification.entity",
                "com.medibook.domain.telemedicine.entity"
        };
    }
}
