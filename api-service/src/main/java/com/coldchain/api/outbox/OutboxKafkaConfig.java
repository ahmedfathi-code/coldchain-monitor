package com.coldchain.api.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

/**
 * Producer used by the outbox relay. Values are already JSON text (written by {@link Outbox}
 * with the shared EventJson mapper), so they are sent as UTF-8 strings: the bytes on Kafka are
 * exactly what our JsonSerializer would produce. acks=all and idempotence come from
 * {@code spring.kafka.producer.*} in application.yml.
 */
@Configuration
public class OutboxKafkaConfig {

    @Bean
    ProducerFactory<String, String> outboxProducerFactory(KafkaProperties kafkaProperties,
                                                          KafkaConnectionDetails connectionDetails) {
        Map<String, Object> config = kafkaProperties.buildProducerProperties();
        // Broker addresses from Spring Boot's connection details, like Boot's own factories.
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getProducer().getBootstrapServers());
        // send() itself blocks while the broker is unreachable (60 s by default). Fail after 10 s
        // instead, so the relay records the failure and retries on its next run.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
    }

    @Bean
    KafkaTemplate<String, String> outboxKafkaTemplate(ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }
}
