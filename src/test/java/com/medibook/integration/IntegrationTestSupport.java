package com.medibook.integration;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.2"))
            .withDatabaseName("medibook_it")
            .withUsername("test")
            .withPassword("test")
            .withStartupTimeout(java.time.Duration.ofMinutes(5));

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(java.time.Duration.ofMinutes(5));

    static {
        System.setProperty("api.version", "1.41");
        mysql.start();
        redis.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host",     redis::getHost);
        registry.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
    }
}
