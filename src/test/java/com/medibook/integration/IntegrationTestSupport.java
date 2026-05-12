package com.medibook.integration;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestSupport {

    private static final MySQLContainer<?> MYSQL;
    private static final GenericContainer<?> REDIS;
    private static final boolean CONTAINERS_RUNNING;
    private static final SchemaMode SCHEMA_MODE;

    static {
        DockerMode dockerMode = dockerMode();
        SchemaMode schemaMode = schemaMode();
        MySQLContainer<?> mysql = null;
        GenericContainer<?> redis = null;
        boolean running = false;

        if (dockerMode != DockerMode.DISABLED && dockerLikelyConfigured() && dockerAvailable()) {
            try {
                mysql = new MySQLContainer<>("mysql:8.2")
                        .withDatabaseName("medibook_it")
                        .withUsername("test")
                        .withPassword("test");
                redis = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                        .withExposedPorts(6379);
                Startables.deepStart(Stream.of(mysql, redis)).join();
                running = true;
            } catch (RuntimeException ex) {
                if (mysql != null) {
                    mysql.stop();
                }
                if (redis != null) {
                    redis.stop();
                }
                mysql = null;
                redis = null;
                if (dockerMode == DockerMode.REQUIRED) {
                    throw new IllegalStateException("""
                            Docker-backed integration tests were required but Testcontainers failed to start.
                            Use -Dmedibook.integration.docker=auto or false for the H2/in-memory Redis fallback.
                            """, ex);
                }
            }
        } else if (dockerMode == DockerMode.REQUIRED) {
            throw new IllegalStateException("""
                    Docker-backed integration tests were required but Docker was not available to Testcontainers.
                    Start Docker and ensure the Docker socket/API is reachable, or use -Dmedibook.integration.docker=auto or false for fallback.
                    """);
        }

        MYSQL = mysql;
        REDIS = redis;
        CONTAINERS_RUNNING = running;
        SCHEMA_MODE = schemaMode;

        if (SCHEMA_MODE == SchemaMode.FLYWAY && !CONTAINERS_RUNNING) {
            throw new IllegalStateException("""
                    Flyway-backed integration tests require Docker-backed MySQL.
                    Run with -Dmedibook.integration.docker=true -Dmedibook.integration.schema=flyway,
                    or use -Dmedibook.integration.schema=hibernate for the H2/create-drop fallback.
                    """);
        }
    }

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.fail-fast", () -> "false");

        if (CONTAINERS_RUNNING) {
            registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
            registry.add("spring.datasource.username", MYSQL::getUsername);
            registry.add("spring.datasource.password", MYSQL::getPassword);
            registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
            registry.add("spring.flyway.enabled", () -> SCHEMA_MODE == SchemaMode.FLYWAY);
            registry.add("spring.jpa.hibernate.ddl-auto",
                    () -> SCHEMA_MODE == SchemaMode.FLYWAY ? "validate" : "create-drop");
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
            registry.add("medibook.test.redis.mode", () -> "container");
            return;
        }

        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:medibook_it;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("medibook.test.redis.mode", () -> "memory");
    }

    private static DockerMode dockerMode() {
        String configured = System.getProperty("medibook.integration.docker", "false")
                .trim()
                .toLowerCase(Locale.ROOT);

        return switch (configured) {
            case "", "auto" -> DockerMode.AUTO;
            case "true", "required" -> DockerMode.REQUIRED;
            case "false", "disabled" -> DockerMode.DISABLED;
            default -> throw new IllegalArgumentException("""
                    Unsupported medibook.integration.docker value: %s.
                    Use auto, true, required, false, or disabled.
                    """.formatted(configured));
        };
    }

    private static SchemaMode schemaMode() {
        String configured = System.getProperty("medibook.integration.schema", "hibernate")
                .trim()
                .toLowerCase(Locale.ROOT);

        return switch (configured) {
            case "", "hibernate", "ddl", "create-drop" -> SchemaMode.HIBERNATE;
            case "flyway", "migration", "migrations" -> SchemaMode.FLYWAY;
            default -> throw new IllegalArgumentException("""
                    Unsupported medibook.integration.schema value: %s.
                    Use hibernate or flyway.
                    """.formatted(configured));
        };
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean dockerLikelyConfigured() {
        if (hasText(System.getenv("DOCKER_HOST"))) {
            return true;
        }
        if (hasText(System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"))) {
            return true;
        }

        String userHome = System.getProperty("user.home");
        return Files.exists(Path.of("/var/run/docker.sock"))
                || (userHome != null && Files.exists(Path.of(userHome, ".docker", "run", "docker.sock")));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private enum DockerMode {
        AUTO,
        REQUIRED,
        DISABLED
    }

    private enum SchemaMode {
        HIBERNATE,
        FLYWAY
    }
}
