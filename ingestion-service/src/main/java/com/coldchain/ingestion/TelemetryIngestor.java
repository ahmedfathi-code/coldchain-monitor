package com.coldchain.ingestion;

import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.common.json.JsonSerializer;
import com.coldchain.common.topic.KafkaTopics;
import com.coldchain.common.topic.MqttTopics;
import com.coldchain.common.topic.MqttTopics.DeviceRef;
import com.coldchain.ingestion.validation.RejectReason;
import com.coldchain.ingestion.validation.TelemetryValidator;
import com.coldchain.ingestion.validation.ValidationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * The heart of the ingestion service: validates one MQTT message and forwards it to Kafka,
 * either to {@code telemetry.raw} (valid) or to {@code telemetry.dlq} (invalid).
 */
@Service
public class TelemetryIngestor {

    // DLQ header names. Headers keep the original payload untouched in the record value.
    public static final String HEADER_ERROR_REASON = "error-reason";
    public static final String HEADER_MQTT_TOPIC = "mqtt-topic";
    public static final String HEADER_RECEIVED_AT = "received-at";

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestor.class);

    private final TelemetryValidator validator;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final JsonSerializer<TelemetryEvent> serializer = new JsonSerializer<>();
    private final Clock clock;
    private final MeterRegistry meters;
    private final Counter accepted;

    public TelemetryIngestor(TelemetryValidator validator, KafkaTemplate<String, byte[]> kafkaTemplate, Clock clock,
                             MeterRegistry meters) {
        this.validator = validator;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.meters = meters;
        this.accepted = Counter.builder("coldchain.telemetry.accepted")
                .description("Valid readings forwarded to telemetry.raw")
                .register(meters);
    }

    /**
     * Validates the message and sends it to Kafka. Blocks until Kafka has confirmed the write
     * (acks=all), and throws if Kafka could not store it, so the caller must not acknowledge
     * the MQTT message in that case.
     */
    public void ingest(String mqttTopic, byte[] payload) {
        ValidationResult result = validator.validate(mqttTopic, payload);
        // Counted only after Kafka confirmed the write.
        if (result.isValid()) {
            sendValid(result.event());
            accepted.increment();
        } else {
            sendToDlq(mqttTopic, payload, result.error());
            rejected(result.reason()).increment();
        }
    }

    /** One counter per reason (Micrometer returns the same counter for the same name and tags). */
    private Counter rejected(RejectReason reason) {
        return Counter.builder("coldchain.telemetry.rejected")
                .description("Invalid messages sent to telemetry.dlq")
                .tag("reason", reason.name().toLowerCase())
                .register(meters);
    }

    private void sendValid(TelemetryEvent event) {
        // Key = deviceId: all readings of a device land on the same partition, in order.
        var record = new ProducerRecord<>(KafkaTopics.TELEMETRY_RAW, event.deviceId(),
                serializer.serialize(KafkaTopics.TELEMETRY_RAW, event));
        kafkaTemplate.send(record).join();
        log.debug("Forwarded reading {} of {}", event.sequence(), event.deviceId());
    }

    private void sendToDlq(String mqttTopic, byte[] payload, String error) {
        // Use the device from the topic as key when we can, so DLQ messages are easy to trace.
        String key = MqttTopics.parseTelemetryTopic(mqttTopic).map(DeviceRef::deviceId).orElse(null);
        var record = new ProducerRecord<>(KafkaTopics.TELEMETRY_DLQ, key, payload);
        record.headers()
                .add(HEADER_ERROR_REASON, error.getBytes(StandardCharsets.UTF_8))
                .add(HEADER_MQTT_TOPIC, mqttTopic.getBytes(StandardCharsets.UTF_8))
                .add(HEADER_RECEIVED_AT, clock.instant().toString().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
        log.warn("Sent invalid message from topic {} to the DLQ: {}", mqttTopic, error);
    }
}
