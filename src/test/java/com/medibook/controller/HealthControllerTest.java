package com.medibook.controller;

import com.medibook.infrastructure.health.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for HealthController.
 */
@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
public class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private com.medibook.security.JwtTokenProvider jwtTokenProvider;

    @MockBean
    private com.medibook.security.CustomUserDetailsService customUserDetailsService;

    @MockBean
    private com.medibook.domain.user.service.SessionTimeoutService sessionTimeoutService;

    @MockBean
    @SuppressWarnings("rawtypes")
    private org.springframework.data.redis.core.RedisTemplate redisTemplate;

    @MockBean
    private DatabaseHealthCheck databaseHealthCheck;

    @MockBean
    private RedisHealthCheck redisHealthCheck;

    @MockBean
    private KafkaHealthCheck kafkaHealthCheck;

    @MockBean
    private CassandraHealthCheck cassandraHealthCheck;

    private HealthCheckResult upResult;
    private HealthCheckResult downResult;

    @BeforeEach
    void setUp() {
        upResult = HealthCheckResult.builder()
                .status("UP")
                .responseTimeMs(2L)
                .lastCheck(Instant.now())
                .build();

        downResult = HealthCheckResult.builder()
                .status("DOWN")
                .responseTimeMs(5000L)
                .lastCheck(Instant.now())
                .build();
    }

    @Test
    void testLivenessProbe_ShouldReturnUpStatus() throws Exception {
        mockMvc.perform(get("/health/live")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checks.runtime").value("OK"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void testReadinessProbe_AllHealthy_ShouldReturnUp() throws Exception {
        when(databaseHealthCheck.check()).thenReturn(upResult);
        when(redisHealthCheck.check()).thenReturn(upResult);
        when(kafkaHealthCheck.check()).thenReturn(upResult);
        when(cassandraHealthCheck.check()).thenReturn(upResult);

        mockMvc.perform(get("/health/ready")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka.status").value("UP"))
                .andExpect(jsonPath("$.components.cassandra.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void testReadinessProbe_DatabaseDown_ShouldReturnDown() throws Exception {
        when(databaseHealthCheck.check()).thenReturn(downResult);
        when(redisHealthCheck.check()).thenReturn(upResult);
        when(kafkaHealthCheck.check()).thenReturn(upResult);
        when(cassandraHealthCheck.check()).thenReturn(upResult);

        mockMvc.perform(get("/health/ready")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.database.status").value("DOWN"));
    }

    @Test
    void testReadinessProbe_RedisDown_ShouldReturnDown() throws Exception {
        when(databaseHealthCheck.check()).thenReturn(upResult);
        when(redisHealthCheck.check()).thenReturn(downResult);
        when(kafkaHealthCheck.check()).thenReturn(upResult);
        when(cassandraHealthCheck.check()).thenReturn(upResult);

        mockMvc.perform(get("/health/ready")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.redis.status").value("DOWN"));
    }

    @Test
    void testReadinessProbe_KafkaDown_ShouldReturnDown() throws Exception {
        when(databaseHealthCheck.check()).thenReturn(upResult);
        when(redisHealthCheck.check()).thenReturn(upResult);
        when(kafkaHealthCheck.check()).thenReturn(downResult);
        when(cassandraHealthCheck.check()).thenReturn(upResult);

        mockMvc.perform(get("/health/ready")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.kafka.status").value("DOWN"));
    }

    @Test
    void testReadinessProbe_CassandraDown_ShouldReturnDown() throws Exception {
        when(databaseHealthCheck.check()).thenReturn(upResult);
        when(redisHealthCheck.check()).thenReturn(upResult);
        when(kafkaHealthCheck.check()).thenReturn(upResult);
        when(cassandraHealthCheck.check()).thenReturn(downResult);

        mockMvc.perform(get("/health/ready")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.cassandra.status").value("DOWN"));
    }

    @Test
    void testReadinessProbe_IncludesResponseTimes() throws Exception {
        when(databaseHealthCheck.check()).thenReturn(upResult);
        when(redisHealthCheck.check()).thenReturn(upResult);
        when(kafkaHealthCheck.check()).thenReturn(upResult);
        when(cassandraHealthCheck.check()).thenReturn(upResult);

        mockMvc.perform(get("/health/ready")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.database.details.response_time_ms").value(2))
                .andExpect(jsonPath("$.components.database.details.last_check").exists());
    }
}
