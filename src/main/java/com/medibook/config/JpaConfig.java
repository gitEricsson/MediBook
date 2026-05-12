package com.medibook.config;

import com.medibook.audit.repository.AuditLogRepository;
import com.medibook.domain.notification.repository.NotificationRepository;
import com.medibook.domain.telemedicine.repository.CassandraChatMessageRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableJpaAuditing
@EnableJpaRepositories(
        basePackages = {
                "com.medibook.config.repository",
                "com.medibook.domain",
                "com.medibook.messaging"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        AuditLogRepository.class,
                        NotificationRepository.class,
                        CassandraChatMessageRepository.class
                }
        )
)
@EnableTransactionManagement
public class JpaConfig {

    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> resolver.setMaxPageSize(50);
    }
}
