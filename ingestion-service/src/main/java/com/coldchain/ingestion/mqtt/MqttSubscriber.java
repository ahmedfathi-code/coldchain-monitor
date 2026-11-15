package com.coldchain.ingestion.mqtt;

import com.coldchain.ingestion.TelemetryIngestor;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Connects to the MQTT broker, subscribes to every device's telemetry topic and hands each
 * message to the {@link TelemetryIngestor}.
 * <p>
 * Delivery guarantee (at least once): Paho acknowledges a QoS 1 message to the broker only
 * after {@link #messageArrived} returns normally. The ingestor blocks until Kafka confirmed
 * the write, and throws if it could not. A thrown exception makes Paho drop the connection
 * without acknowledging, the client reconnects automatically, and because our session is
 * persistent ({@code cleanSession=false}) the broker delivers the message again.
 * <p>
 * {@link SmartLifecycle} makes Spring start this bean after every other bean is ready (so
 * Kafka is usable before the first message arrives) and stop it first on shutdown.
 */
@Component
public class MqttSubscriber implements MqttCallbackExtended, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscriber.class);

    private static final int QOS_AT_LEAST_ONCE = 1;

    private final MqttProperties properties;
    private final TelemetryIngestor ingestor;
    private final MqttClient client;
    private volatile boolean running = false;

    public MqttSubscriber(MqttProperties properties, TelemetryIngestor ingestor) throws MqttException {
        this.properties = properties;
        this.ingestor = ingestor;
        this.client = new MqttClient(properties.url(), properties.clientId(), new MemoryPersistence());
        this.client.setCallback(this);
    }

    @Override
    public void start() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(properties.username());
        options.setPassword(properties.password().toCharArray());
        options.setAutomaticReconnect(true);
        // Persistent session: while we are offline the broker keeps our subscription and
        // queues QoS 1 messages for us, instead of dropping them.
        options.setCleanSession(false);
        try {
            client.connect(options);
            running = true;
        } catch (MqttException e) {
            throw new IllegalStateException("Could not connect to MQTT broker " + properties.url(), e);
        }
    }

    /** Called after the first connect and after every automatic reconnect. */
    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        log.info("{} MQTT broker {}", reconnect ? "Reconnected to" : "Connected to", serverUri);
        try {
            // Subscribing again is harmless and covers a broker that lost our session.
            client.subscribe(properties.topicFilter(), QOS_AT_LEAST_ONCE);
            log.info("Subscribed to {}", properties.topicFilter());
        } catch (MqttException e) {
            log.error("Could not subscribe to {}", properties.topicFilter(), e);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Throws if Kafka is unavailable: the message is then NOT acknowledged (see class doc).
        ingestor.ingest(topic, message.getPayload());
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost ({}), reconnecting automatically", cause.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Only called for messages we publish; this service never publishes to MQTT.
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            log.warn("Error while closing the MQTT client", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Used by the health indicator. */
    public boolean isConnected() {
        return client.isConnected();
    }
}
