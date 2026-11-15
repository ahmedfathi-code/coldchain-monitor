package com.coldchain.simulator;

import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.common.json.EventJson;
import com.coldchain.common.topic.MqttTopics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Publishes telemetry readings to the MQTT broker (Mosquitto). */
@Component
public class MqttTelemetryPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttTelemetryPublisher.class);

    /** QoS 1 = "at least once": the broker confirms every message (PUBACK). */
    private static final int QOS_AT_LEAST_ONCE = 1;

    private final SimulatorProperties.Mqtt settings;
    private final MqttClient client;

    public MqttTelemetryPublisher(SimulatorProperties properties) throws MqttException {
        this.settings = properties.mqtt();
        // MemoryPersistence: in-flight messages are kept in memory (enough for a simulator).
        this.client = new MqttClient(settings.url(), settings.clientId(), new MemoryPersistence());
    }

    @PostConstruct
    void connect() throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(settings.username());
        options.setPassword(settings.password().toCharArray());
        // If the connection drops later, Paho reconnects by itself.
        options.setAutomaticReconnect(true);
        // The simulator only publishes, so it has no subscriptions worth keeping on the broker.
        options.setCleanSession(true);
        client.connect(options);
        log.info("Connected to MQTT broker {} as {}", settings.url(), settings.username());
    }

    /** Sends one reading. Blocks until the broker has confirmed it (QoS 1). */
    public void publish(TelemetryEvent event) throws MqttException {
        publishRaw(MqttTopics.telemetryTopic(event.tenantId(), event.deviceId()), EventJson.mapper().writeValueAsBytes(event));
    }

    /** Sends arbitrary bytes, used by the INVALID_PAYLOAD scenario to send broken messages. */
    public void publishRaw(String topic, byte[] payload) throws MqttException {
        MqttMessage message = new MqttMessage(payload);
        message.setQos(QOS_AT_LEAST_ONCE);
        client.publish(topic, message);
    }

    @PreDestroy
    void disconnect() throws MqttException {
        if (client.isConnected()) {
            client.disconnect();
        }
        client.close();
    }
}
