package com.coldchain.streams.latest;

import com.coldchain.common.event.TelemetryEvent;
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
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.Map;

/**
 * Configures how {@code @KafkaListener} methods receive telemetry: keys as strings, values
 * deserialized into {@link TelemetryEvent} with our shared JSON deserializer.
 */
@Configuration
public class KafkaConsumerConfig {

    /**
     * Named "kafkaListenerContainerFactory" so it replaces Spring Boot's default one.
     * Connection settings (bootstrap servers, auto-offset-reset, ...) still come from
     * {@code spring.kafka.*} in application.yml.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent> kafkaListenerContainerFactory(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails, MeterRegistry meters) {
        // ErrorHandlingDeserializer wraps ours: a record that cannot be deserialized (a
        // "poison pill") is logged and skipped by the error handler, instead of making the
        // consumer fail on the same record forever.
        var valueDeserializer = new ErrorHandlingDeserializer<>(new JsonDeserializer<>(TelemetryEvent.class));
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        // Take the broker addresses from Spring Boot's connection details (not only from
        // application.yml), exactly like Boot's own factories: this also honours overrides
        // such as Testcontainers' @ServiceConnection.
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getConsumer().getBootstrapServers());
        var consumerFactory = new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
        // Kafka client metrics (e.g. consumer lag) for Prometheus; Boot adds this only to its own factories.
        consumerFactory.addListener(new MicrometerConsumerListener<>(meters));

        var factory = new ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}
