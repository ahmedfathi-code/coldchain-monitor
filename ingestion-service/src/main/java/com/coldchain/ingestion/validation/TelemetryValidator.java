package com.coldchain.ingestion.validation;

import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.common.json.EventJson;
import com.coldchain.common.topic.MqttTopics;
import com.coldchain.common.topic.MqttTopics.DeviceRef;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether an MQTT message is a valid telemetry reading:
 * <ol>
 *     <li>the topic has the expected shape,</li>
 *     <li>the payload is telemetry JSON,</li>
 *     <li>every field is present,</li>
 *     <li>every value is physically sane,</li>
 *     <li>the tenant and device in the payload are the ones in the topic.</li>
 * </ol>
 * All problems found are reported together, so the DLQ message says everything that is wrong.
 */
@Component
public class TelemetryValidator {

    // Sane sensor limits. Ultra-low-temperature vaccine freezers go down to about -80 °C.
    static final double MIN_TEMPERATURE = -80.0;
    static final double MAX_TEMPERATURE = 60.0;
    /** A device clock may be a bit ahead of ours, but not by more than this. */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final Clock clock;

    public TelemetryValidator(Clock clock) {
        this.clock = clock;
    }

    public ValidationResult validate(String mqttTopic, byte[] payload) {
        Optional<DeviceRef> topicDevice = MqttTopics.parseTelemetryTopic(mqttTopic);
        if (topicDevice.isEmpty()) {
            return ValidationResult.invalid(RejectReason.WRONG_TOPIC, "Unexpected topic '" + mqttTopic
                    + "', expected coldchain/{tenantId}/devices/{deviceId}/telemetry");
        }

        TelemetryEvent event;
        try {
            event = EventJson.mapper().readValue(payload, TelemetryEvent.class);
        } catch (JacksonException e) {
            return ValidationResult.invalid(RejectReason.INVALID_JSON, "Payload is not valid telemetry JSON: " + e.getOriginalMessage());
        }
        if (event == null) {
            return ValidationResult.invalid(RejectReason.INVALID_JSON, "Payload is empty");
        }

        List<String> missing = findMissingFields(event);
        if (!missing.isEmpty()) {
            // No point checking ranges when fields are missing.
            return ValidationResult.invalid(RejectReason.MISSING_FIELDS, "Missing required fields: " + String.join(", ", missing));
        }

        List<String> errors = new ArrayList<>();
        checkRanges(event, errors);
        checkIdsMatchTopic(event, topicDevice.get(), errors);

        return errors.isEmpty()
                ? ValidationResult.valid(event)
                : ValidationResult.invalid(RejectReason.INVALID_VALUES, String.join("; ", errors));
    }

    private List<String> findMissingFields(TelemetryEvent event) {
        List<String> missing = new ArrayList<>();
        if (event.deviceId() == null || event.deviceId().isBlank()) {
            missing.add("deviceId");
        }
        if (event.tenantId() == null || event.tenantId().isBlank()) {
            missing.add("tenantId");
        }
        if (event.timestamp() == null) {
            missing.add("timestamp");
        }
        if (event.sequence() == null) {
            missing.add("sequence");
        }
        if (event.temperature() == null) {
            missing.add("temperature");
        }
        if (event.humidity() == null) {
            missing.add("humidity");
        }
        if (event.doorOpen() == null) {
            missing.add("doorOpen");
        }
        if (event.batteryLevel() == null) {
            missing.add("batteryLevel");
        }
        return missing;
    }

    private void checkRanges(TelemetryEvent event, List<String> errors) {
        if (event.sequence() < 0) {
            errors.add("sequence must not be negative");
        }
        if (event.temperature() < MIN_TEMPERATURE || event.temperature() > MAX_TEMPERATURE) {
            errors.add("temperature " + event.temperature() + " is outside [" + MIN_TEMPERATURE + ", " + MAX_TEMPERATURE + "]");
        }
        if (event.humidity() < 0 || event.humidity() > 100) {
            errors.add("humidity " + event.humidity() + " is outside [0, 100]");
        }
        if (event.batteryLevel() < 0 || event.batteryLevel() > 100) {
            errors.add("batteryLevel " + event.batteryLevel() + " is outside [0, 100]");
        }
        if (event.timestamp().isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) {
            errors.add("timestamp " + event.timestamp() + " is in the future");
        }
    }

    private void checkIdsMatchTopic(TelemetryEvent event, DeviceRef topicDevice, List<String> errors) {
        if (!event.tenantId().equals(topicDevice.tenantId())) {
            errors.add("tenantId '" + event.tenantId() + "' does not match topic tenant '" + topicDevice.tenantId() + "'");
        }
        if (!event.deviceId().equals(topicDevice.deviceId())) {
            errors.add("deviceId '" + event.deviceId() + "' does not match topic device '" + topicDevice.deviceId() + "'");
        }
    }
}
