package com.coldchain.api.alert;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.json.JsonDeserializer;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/** How {@code @KafkaListener} methods receive alerts, and what happens when handling fails. */
@Configuration
public class AlertConsumerConfig {

    /** Named "kafkaListenerContainerFactory" so it replaces Spring Boot's default one. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, AlertEvent> kafkaListenerContainerFactory(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails, MeterRegistry meters) {
        // A record that cannot be deserialized is logged and skipped by the error handler.
        var valueDeserializer = new ErrorHandlingDeserializer<>(new JsonDeserializer<>(AlertEvent.class));
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        // Take the broker addresses from Spring Boot's connection details (not only from
        // application.yml), exactly like Boot's own factories: this also honours overrides
        // such as Testcontainers' @ServiceConnection.
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getConsumer().getBootstrapServers());
        var consumerFactory = new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
        // Kafka client metrics (e.g. consumer lag) for Prometheus; Boot adds this only to its own factories.
        consumerFactory.addListener(new MicrometerConsumerListener<>(meters));

        var factory = new ConcurrentKafkaListenerContainerFactory<String, AlertEvent>();
        factory.setConsumerFactory(consumerFactory);
        // If handling fails (e.g. Telegram is unreachable), retry the same record 3 more times,
        // 5 seconds apart; after that it is logged and skipped so later alerts are not blocked.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5_000L, 3)));
        return factory;
    }
}
