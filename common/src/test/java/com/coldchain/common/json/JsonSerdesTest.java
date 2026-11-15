package com.coldchain.common.json;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertTransition;
import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.Severity;
import com.coldchain.common.event.TelemetryEvent;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serde;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSerdesTest {

    private static final String TOPIC = "any-topic";

    private final Serde<TelemetryEvent> telemetrySerde = JsonSerdes.telemetry();

    private final TelemetryEvent reading = new TelemetryEvent(
            "fridge-001", "pharmacy-a", Instant.parse("2026-01-15T10:00:00Z"),
            42L, 4.5, 55.0, false, 87);

    @Test
    void telemetryRoundTripKeepsAllFields() {
        byte[] bytes = telemetrySerde.serializer().serialize(TOPIC, reading);
        TelemetryEvent back = telemetrySerde.deserializer().deserialize(TOPIC, bytes);

        assertThat(back).isEqualTo(reading);
    }

    @Test
    void telemetryIsWrittenWithContractFieldNamesAndIsoTimestamp() {
        String json = new String(telemetrySerde.serializer().serialize(TOPIC, reading), StandardCharsets.UTF_8);

        assertThat(json)
                .contains("\"deviceId\":\"fridge-001\"")
                .contains("\"tenantId\":\"pharmacy-a\"")
                .contains("\"timestamp\":\"2026-01-15T10:00:00Z\"")
                .contains("\"sequence\":42")
                .contains("\"temperature\":4.5")
                .contains("\"humidity\":55.0")
                .contains("\"doorOpen\":false")
                .contains("\"batteryLevel\":87");
    }

    @Test
    void telemetryIsReadFromContractJson() {
        // Exactly the shape a device sends over MQTT (see PLAN.md "Contracts").
        String json = """
                {"deviceId":"truck-007","tenantId":"pharmacy-b","timestamp":"2026-01-15T10:00:00.250Z",
                 "sequence":7,"temperature":3.2,"humidity":61.5,"doorOpen":true,"batteryLevel":14}
                """;

        TelemetryEvent event = deserializeTelemetry(json);

        assertThat(event).isEqualTo(new TelemetryEvent(
                "truck-007", "pharmacy-b", Instant.parse("2026-01-15T10:00:00.250Z"),
                7L, 3.2, 61.5, true, 14));
    }

    @Test
    void missingFieldsBecomeNullSoTheyCanBeValidated() {
        TelemetryEvent event = deserializeTelemetry("{\"deviceId\":\"fridge-001\"}");

        assertThat(event.deviceId()).isEqualTo("fridge-001");
        assertThat(event.temperature()).isNull();
        assertThat(event.doorOpen()).isNull();
        assertThat(event.batteryLevel()).isNull();
    }

    @Test
    void unknownFieldsAreIgnored() {
        TelemetryEvent event = deserializeTelemetry("{\"deviceId\":\"fridge-001\",\"firmware\":\"1.2.3\"}");

        assertThat(event.deviceId()).isEqualTo("fridge-001");
    }

    @Test
    void invalidJsonThrowsKafkaSerializationException() {
        assertThatThrownBy(() -> deserializeTelemetry("this is not json"))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("TelemetryEvent");
    }

    @Test
    void nullStaysNullInBothDirections() {
        // Needed for tombstones (delete markers) on the compacted device.config topic.
        Serde<DeviceConfigEvent> serde = JsonSerdes.deviceConfig();

        assertThat(serde.serializer().serialize(TOPIC, null)).isNull();
        assertThat(serde.deserializer().deserialize(TOPIC, null)).isNull();
    }

    @Test
    void alertRoundTripKeepsEnumsAndDetails() {
        AlertEvent alert = new AlertEvent(
                "3f2b8c1e-0000-4000-8000-000000000001", "fridge-001", "pharmacy-a",
                AlertType.TEMP_EXCURSION, AlertTransition.OPENED, Severity.CRITICAL,
                Instant.parse("2026-01-15T10:15:00Z"),
                Map.of("temperature", 9.4, "maxTemp", 8.0, "durationMinutes", 15));
        Serde<AlertEvent> serde = JsonSerdes.alert();

        byte[] bytes = serde.serializer().serialize(TOPIC, alert);
        AlertEvent back = serde.deserializer().deserialize(TOPIC, bytes);

        assertThat(back).isEqualTo(alert);
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .contains("\"type\":\"TEMP_EXCURSION\"")
                .contains("\"transition\":\"OPENED\"")
                .contains("\"detectedAt\":\"2026-01-15T10:15:00Z\"");
    }

    @Test
    void deviceConfigRoundTrip() {
        DeviceConfigEvent config = new DeviceConfigEvent("truck-007", "pharmacy-b", 2.0, 8.0, 10, 3, false);
        Serde<DeviceConfigEvent> serde = JsonSerdes.deviceConfig();

        byte[] bytes = serde.serializer().serialize(TOPIC, config);

        assertThat(serde.deserializer().deserialize(TOPIC, bytes)).isEqualTo(config);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"active\":false");
    }

    @Test
    void deviceConfigWrittenBeforeTheActiveFieldExistedMeansActive() {
        // Old messages stay on the compacted device.config topic; they must not disable devices.
        String json = """
                {"deviceId":"truck-007","tenantId":"pharmacy-b","minTemp":2.0,"maxTemp":8.0,
                 "excursionMinutes":10,"doorOpenMaxMinutes":3}""";

        DeviceConfigEvent config = JsonSerdes.deviceConfig().deserializer()
                .deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8));

        assertThat(config.active()).isTrue();
    }

    private TelemetryEvent deserializeTelemetry(String json) {
        return telemetrySerde.deserializer().deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8));
    }
}
