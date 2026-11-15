package com.coldchain.common.topic;

import java.util.Optional;

/**
 * MQTT topic contract: {@code coldchain/{tenantId}/devices/{deviceId}/telemetry}.
 * The device simulator publishes to it and the ingestion service subscribes to it.
 */
public final class MqttTopics {

    /**
     * Subscription filter matching the telemetry topic of every device of every tenant.
     * In MQTT, {@code +} is a wildcard for exactly one topic level.
     */
    public static final String TELEMETRY_FILTER = "coldchain/+/devices/+/telemetry";

    private MqttTopics() {
        // constants and helpers only
    }

    /** The tenant and device a telemetry topic belongs to. */
    public record DeviceRef(String tenantId, String deviceId) {
    }

    /** Builds the topic a given device publishes its telemetry to. */
    public static String telemetryTopic(String tenantId, String deviceId) {
        return "coldchain/" + tenantId + "/devices/" + deviceId + "/telemetry";
    }

    /**
     * Extracts tenantId and deviceId from a telemetry topic.
     *
     * @return the ids, or empty if the topic does not have the expected shape
     */
    public static Optional<DeviceRef> parseTelemetryTopic(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        // limit -1 keeps empty levels, so "coldchain//devices/x/telemetry" is rejected
        String[] levels = topic.split("/", -1);
        boolean validShape = levels.length == 5
                && levels[0].equals("coldchain")
                && levels[2].equals("devices")
                && levels[4].equals("telemetry")
                && !levels[1].isBlank()
                && !levels[3].isBlank();
        if (!validShape) {
            return Optional.empty();
        }
        return Optional.of(new DeviceRef(levels[1], levels[3]));
    }
}
