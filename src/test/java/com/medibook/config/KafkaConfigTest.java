package com.medibook.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KafkaConfig")
class KafkaConfigTest {

    private KafkaConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:1");
    }

    @Test
    void producerFactoryUsesProductionSafeDefaults() {
        DefaultKafkaProducerFactory<String, Object> producerFactory =
                (DefaultKafkaProducerFactory<String, Object>) config.producerFactory();

        Map<String, Object> properties = producerFactory.getConfigurationProperties();
        assertThat(properties)
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1")
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.RETRIES_CONFIG, 5)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
    }

    @Test
    void listenerFactoriesUseManualImmediateAckAndConcurrency() {
        KafkaTemplate<String, Object> kafkaTemplate = config.kafkaTemplate();

        ConcurrentKafkaListenerContainerFactory<String, ?> appointmentFactory =
                config.appointmentKafkaListenerContainerFactory(kafkaTemplate);
        ConcurrentKafkaListenerContainerFactory<String, ?> auditFactory =
                config.auditKafkaListenerContainerFactory(kafkaTemplate);

        assertThat(appointmentFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(auditFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(ReflectionTestUtils.getField(appointmentFactory, "concurrency")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(auditFactory, "concurrency")).isEqualTo(2);
    }

    @Test
    void consumerFactoriesDisableAutoCommit() {
        Map<String, Object> properties = config.appointmentConsumerFactory().getConfigurationProperties();

        assertThat(properties)
                .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1")
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "medibook-group")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    @Test
    void topicsUseThreePartitionsForParallelConsumption() {
        assertTopic(config.appointmentEventsTopic(), "appointment.events");
        assertTopic(config.appointmentEventsDltTopic(), "appointment.events.DLT");
        assertTopic(config.auditEventsTopic(), "audit.events");
        assertTopic(config.auditEventsDltTopic(), "audit.events.DLT");
        assertTopic(config.notificationTopic(), "notification.events");
    }

    @Test
    void kafkaHealthReportsDownWhenBrokerIsUnavailable() {
        assertThat(config.kafkaHealthIndicator().health().getStatus()).isEqualTo(Status.DOWN);
    }

    private static void assertTopic(NewTopic topic, String name) {
        assertThat(topic.name()).isEqualTo(name);
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}
