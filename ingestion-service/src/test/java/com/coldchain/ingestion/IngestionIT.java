package com.coldchain.ingestion;

import com.coldchain.common.topic.KafkaTopics;
import com.coldchain.common.topic.MqttTopics;
import com.coldchain.ingestion.mqtt.MqttHealthIndicator;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole ingestion service against a real Mosquitto (started with the project's own
 * infra/mosquitto config, password accounts and ACL) and a real Kafka:
 * a message published over MQTT must end up in telemetry.raw when valid, and in
 * telemetry.dlq (untouched, with the reason in a header) when invalid.
 */
@SpringBootTest
@Testcontainers
class IngestionIT {

    private static final String SIMULATOR_PASSWORD = "simulator-test-password";
    private static final String INGESTION_PASSWORD = "ingestion-test-password";
    private static final String TENANT = "nile-pharmacy";

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    @Container
    static final GenericContainer<?> MOSQUITTO = new GenericContainer<>("eclipse-mosquitto:2.0.22")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/mosquitto/mosquitto.conf"),
                    "/mosquitto/config/mosquitto.conf")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/mosquitto/acl"), "/mosquitto/config/acl")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/mosquitto/init.sh"), "/mosquitto/init.sh")
            .withEnv("MQTT_SIMULATOR_PASSWORD", SIMULATOR_PASSWORD)
            .withEnv("MQTT_INGESTION_PASSWORD", INGESTION_PASSWORD)
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "/mosquitto/init.sh"))
            .withExposedPorts(1883)
            .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1));

    /** Points the ingestion service at the Mosquitto container. */
    @DynamicPropertySource
    static void mqttSettings(DynamicPropertyRegistry registry) {
        registry.add("mqtt.url", IngestionIT::brokerUrl);
        registry.add("mqtt.password", () -> INGESTION_PASSWORD);
    }

    private static MqttClient device;

    @Autowired
    private MqttHealthIndicator mqttHealth;

    /** Creates the topics like infra/kafka/create-topics.sh does, and connects a fake device. */
    @BeforeAll
    static void createTopicsAndConnectDevice() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(KafkaTopics.TELEMETRY_RAW, 3, (short) 1),
                    new NewTopic(KafkaTopics.TELEMETRY_DLQ, 1, (short) 1))).all().get();
        }
        device = new MqttClient(brokerUrl(), "test-device", new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName("simulator");
        options.setPassword(SIMULATOR_PASSWORD.toCharArray());
        device.connect(options);
    }

    @AfterAll
    static void disconnectDevice() throws MqttException {
        device.disconnect();
        device.close();
    }

    @Test
    void theServiceIsConnectedToTheBroker() {
        assertThat(mqttHealth.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void validReadingIsForwardedToTelemetryRawKeyedByDevice() throws Exception {
        String deviceId = "valid-" + UUID.randomUUID();
        String payload = reading(deviceId, TENANT, "4.5");

        publish(deviceId, payload);

        ConsumerRecord<String, byte[]> record = awaitRecord(KafkaTopics.TELEMETRY_RAW, deviceId);
        assertThat(record.key()).isEqualTo(deviceId);
        assertThat(text(record.value()))
                .contains("\"deviceId\":\"" + deviceId + "\"")
                .contains("\"temperature\":4.5");
    }

    /** The four kinds of broken payload the INVALID_PAYLOAD simulator scenario sends. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "not JSON           | temperature=4.5;garbage                          | Payload is not valid telemetry JSON",
            "missing field      | {\"deviceId\":\"DEVICE\",\"tenantId\":\"nile-pharmacy\"} | Missing required fields",
            "impossible value   | READING_999                                     | temperature 999.0 is outside",
            "tenant mismatch    | READING_OTHER_TENANT                            | does not match topic tenant"
    })
    void invalidMessageGoesToTheDlqUntouchedWithTheReason(String kind, String template, String expectedReason)
            throws Exception {
        String deviceId = "invalid-" + UUID.randomUUID();
        String payload = switch (template) {
            case "READING_999" -> reading(deviceId, TENANT, "999.0");
            case "READING_OTHER_TENANT" -> reading(deviceId, "someone-else", "4.5");
            default -> template.replace("DEVICE", deviceId);
        };

        publish(deviceId, payload);

        ConsumerRecord<String, byte[]> record = awaitRecord(KafkaTopics.TELEMETRY_DLQ, deviceId);
        assertThat(text(record.value())).as("original payload, byte for byte").isEqualTo(payload);
        assertThat(header(record, TelemetryIngestor.HEADER_ERROR_REASON)).contains(expectedReason);
        assertThat(header(record, TelemetryIngestor.HEADER_MQTT_TOPIC))
                .isEqualTo(MqttTopics.telemetryTopic(TENANT, deviceId));
        assertThat(header(record, TelemetryIngestor.HEADER_RECEIVED_AT)).isNotBlank();
        assertThat(findRecord(KafkaTopics.TELEMETRY_RAW, deviceId, Duration.ofSeconds(2)))
                .as("never forwarded to telemetry.raw").isEmpty();
    }

    private static void publish(String deviceId, String payload) throws MqttException {
        device.publish(MqttTopics.telemetryTopic(TENANT, deviceId), payload.getBytes(StandardCharsets.UTF_8), 1, false);
    }

    private static String reading(String deviceId, String tenantId, String temperature) {
        return "{\"deviceId\":\"" + deviceId + "\",\"tenantId\":\"" + tenantId + "\",\"timestamp\":\""
                + Instant.now().truncatedTo(ChronoUnit.MILLIS) + "\",\"sequence\":1,\"temperature\":" + temperature
                + ",\"humidity\":50.0,\"doorOpen\":false,\"batteryLevel\":90}";
    }

    private static ConsumerRecord<String, byte[]> awaitRecord(String topic, String key) {
        return findRecord(topic, key, Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("No record with key " + key + " on " + topic));
    }

    /** Reads the topic from the beginning until a record with this key appears (or the time is up). */
    private static Optional<ConsumerRecord<String, byte[]>> findRecord(String topic, String key, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return Optional.of(record);
                    }
                }
            }
            return Optional.empty();
        }
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        return text(record.headers().lastHeader(name).value());
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String brokerUrl() {
        return "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883);
    }
}
