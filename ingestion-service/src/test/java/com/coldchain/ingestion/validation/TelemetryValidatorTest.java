package com.coldchain.ingestion.validation;

import com.coldchain.common.event.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryValidatorTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String TOPIC = "coldchain/nile-pharmacy/devices/nile-fridge-01/telemetry";

    private final TelemetryValidator validator = new TelemetryValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsAValidReading() {
        ValidationResult result = validate(TOPIC, validJson());

        assertThat(result.isValid()).isTrue();
        assertThat(result.event()).isEqualTo(new TelemetryEvent(
                "nile-fridge-01", "nile-pharmacy", Instant.parse("2026-01-15T09:59:55Z"),
                12L, 4.8, 51.2, false, 88));
    }

    @Test
    void rejectsAnUnexpectedTopic() {
        ValidationResult result = validate("coldchain/nile-pharmacy/nile-fridge-01", validJson());

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.WRONG_TOPIC);
        assertThat(result.error()).contains("Unexpected topic");
    }

    @Test
    void rejectsSomethingThatIsNotJson() {
        ValidationResult result = validate(TOPIC, "hello, I am not JSON");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.INVALID_JSON);
        assertThat(result.error()).startsWith("Payload is not valid telemetry JSON");
    }

    @Test
    void rejectsAFieldWithTheWrongType() {
        ValidationResult result = validate(TOPIC, validJson().replace("4.8", "\"warm\""));

        assertThat(result.error()).startsWith("Payload is not valid telemetry JSON");
    }

    @Test
    void rejectsAnEmptyPayload() {
        assertThat(validate(TOPIC, "null")).satisfies(result -> {
            assertThat(result.reason()).isEqualTo(RejectReason.INVALID_JSON);
            assertThat(result.error()).isEqualTo("Payload is empty");
        });
    }

    @Test
    void listsEveryMissingField() {
        ValidationResult result = validate(TOPIC, """
                {"deviceId":"nile-fridge-01","tenantId":"nile-pharmacy","sequence":1,"humidity":50}
                """);

        assertThat(result.reason()).isEqualTo(RejectReason.MISSING_FIELDS);
        assertThat(result.error()).isEqualTo("Missing required fields: timestamp, temperature, doorOpen, batteryLevel");
    }

    @Test
    void treatsBlankIdsAsMissing() {
        ValidationResult result = validate(TOPIC, validJson().replace("\"nile-fridge-01\"", "\"  \""));

        assertThat(result.error()).isEqualTo("Missing required fields: deviceId");
    }

    @Test
    void rejectsValuesOutsideSaneRanges() {
        String json = validJson()
                .replace("4.8", "150.0")
                .replace("51.2", "-3")
                .replace("88", "101")
                .replace("\"sequence\":12", "\"sequence\":-1");

        ValidationResult result = validate(TOPIC, json);

        assertThat(result.reason()).isEqualTo(RejectReason.INVALID_VALUES);
        assertThat(result.error())
                .contains("sequence must not be negative")
                .contains("temperature 150.0 is outside")
                .contains("humidity -3.0 is outside")
                .contains("batteryLevel 101 is outside");
    }

    @Test
    void acceptsBoundaryValues() {
        String json = validJson().replace("4.8", "-80.0").replace("51.2", "100").replace("88", "0");

        assertThat(validate(TOPIC, json).isValid()).isTrue();
    }

    @Test
    void acceptsSmallClockSkewButRejectsTimestampsFarInTheFuture() {
        String slightlyAhead = validJson().replace("2026-01-15T09:59:55Z", "2026-01-15T10:04:00Z");
        String farAhead = validJson().replace("2026-01-15T09:59:55Z", "2026-01-15T10:06:00Z");

        assertThat(validate(TOPIC, slightlyAhead).isValid()).isTrue();
        assertThat(validate(TOPIC, farAhead).error()).contains("is in the future");
    }

    @Test
    void rejectsIdsThatDoNotMatchTheTopic() {
        String otherTopic = "coldchain/delta-pharmacy/devices/delta-fridge-01/telemetry";

        ValidationResult result = validate(otherTopic, validJson());

        assertThat(result.reason()).isEqualTo(RejectReason.INVALID_VALUES);
        assertThat(result.error())
                .contains("tenantId 'nile-pharmacy' does not match topic tenant 'delta-pharmacy'")
                .contains("deviceId 'nile-fridge-01' does not match topic device 'delta-fridge-01'");
    }

    private ValidationResult validate(String topic, String payload) {
        return validator.validate(topic, payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String validJson() {
        return """
                {"deviceId":"nile-fridge-01","tenantId":"nile-pharmacy","timestamp":"2026-01-15T09:59:55Z",
                 "sequence":12,"temperature":4.8,"humidity":51.2,"doorOpen":false,"batteryLevel":88}
                """;
    }
}
