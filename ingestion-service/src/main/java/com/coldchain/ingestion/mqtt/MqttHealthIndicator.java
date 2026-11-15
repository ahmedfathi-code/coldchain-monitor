package com.coldchain.ingestion.mqtt;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Adds an "mqtt" entry to /actuator/health: UP while connected to the broker, DOWN otherwise.
 * Spring Boot combines all indicators, so the whole service reports DOWN when MQTT is down.
 */
@Component
public class MqttHealthIndicator implements HealthIndicator {

    private final MqttSubscriber subscriber;
    private final MqttProperties properties;

    public MqttHealthIndicator(MqttSubscriber subscriber, MqttProperties properties) {
        this.subscriber = subscriber;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder builder = subscriber.isConnected() ? Health.up() : Health.down();
        return builder.withDetail("broker", properties.url()).build();
    }
}
