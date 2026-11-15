package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

/**
 * Lets Spring create and manage the KafkaStreams instance (start, stop, configuration from
 * {@code spring.kafka.streams.*}); we only describe the topology.
 */
@Configuration
@EnableKafkaStreams
public class AlertStreamsConfig {

    @Bean
    KStream<String, AlertEvent> alertStream(StreamsBuilder builder, RulesProperties rules, MeterRegistry meters) {
        KStream<String, AlertEvent> alerts = AlertTopology.build(builder, rules);
        // Count every alert event for the dashboard. (Under exactly-once, a transaction that is
        // retried after a failure counts its alerts again: fine for a rate, not an audit log.)
        alerts.peek((deviceId, alert) -> Counter.builder("coldchain.alerts.emitted")
                .description("Alert events produced to the alerts topic")
                .tag("type", alert.type().name())
                .tag("transition", alert.transition().name())
                .register(meters)
                .increment());
        return alerts;
    }
}
