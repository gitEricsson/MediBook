package com.medibook.config;

import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.event.AuditEvent;
import com.medibook.messaging.event.PaymentEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@Profile("!test")
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean listenerAutoStartup;

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.RETRIES_CONFIG, 5);
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AppointmentEvent> appointmentKafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, AppointmentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(appointmentConsumerFactory());
        factory.setConcurrency(3);
        factory.setAutoStartup(listenerAutoStartup);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(buildErrorHandler(kafkaTemplate));
        return factory;
    }

    @Bean
    public ConsumerFactory<String, AppointmentEvent> appointmentConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(baseConsumerProps(), new StringDeserializer(),
                new JsonDeserializer<>(AppointmentEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AuditEvent> auditKafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, AuditEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(auditConsumerFactory());
        factory.setConcurrency(2);
        factory.setAutoStartup(listenerAutoStartup);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(buildErrorHandler(kafkaTemplate));
        return factory;
    }

    @Bean
    public ConsumerFactory<String, AuditEvent> auditConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(baseConsumerProps(), new StringDeserializer(),
                new JsonDeserializer<>(AuditEvent.class));
    }

    /** 3 retries with 1-second backoff, then route to <topic>.DLT */
    private DefaultErrorHandler buildErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> paymentKafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(
                baseConsumerProps(), new StringDeserializer(), new JsonDeserializer<>(PaymentEvent.class)));
        factory.setConcurrency(2);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(buildErrorHandler(kafkaTemplate));
        return factory;
    }

    @Bean
    public NewTopic appointmentEventsTopic() {
        return TopicBuilder.name("appointment.events").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic appointmentEventsDltTopic() {
        return TopicBuilder.name("appointment.events.DLT").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder.name("audit.events").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic auditEventsDltTopic() {
        return TopicBuilder.name("audit.events.DLT").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic notificationTopic() {
        return TopicBuilder.name("notification.events").partitions(3).replicas(1).build();
    }

    @Bean public NewTopic notificationEventsDltTopic()  { return TopicBuilder.name("notification.events.DLT").partitions(3).replicas(1).build(); }
    @Bean public NewTopic paymentEventsTopic()          { return TopicBuilder.name("payment.events").partitions(3).replicas(1).build(); }
    @Bean public NewTopic paymentEventsDltTopic()       { return TopicBuilder.name("payment.events.DLT").partitions(3).replicas(1).build(); }
    @Bean public NewTopic telemedicineEventsTopic()     { return TopicBuilder.name("telemedicine.events").partitions(3).replicas(1).build(); }
    @Bean public NewTopic reviewEventsTopic()           { return TopicBuilder.name("review.events").partitions(2).replicas(1).build(); }
    @Bean public NewTopic waitlistEventsTopic()         { return TopicBuilder.name("waitlist.events").partitions(2).replicas(1).build(); }
    @Bean public NewTopic consentEventsTopic()          { return TopicBuilder.name("consent.events").partitions(2).replicas(1).build(); }

    @Bean
    public HealthIndicator kafkaHealthIndicator() {
        return () -> {
            Map<String, Object> props = Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000",
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000"
            );
            try (AdminClient adminClient = AdminClient.create(props)) {
                int brokerCount = adminClient.describeCluster().nodes().get(3, TimeUnit.SECONDS).size();
                return Health.up().withDetail("brokers", brokerCount).build();
            } catch (Exception ex) {
                return Health.down(ex).build();
            }
        };
    }

    private Map<String, Object> baseConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "medibook-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }
}
