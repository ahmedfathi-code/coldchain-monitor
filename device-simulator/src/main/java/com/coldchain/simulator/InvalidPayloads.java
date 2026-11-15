package com.coldchain.simulator;

import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.common.json.EventJson;

import java.nio.charset.StandardCharsets;

/**
 * Broken messages for the INVALID_PAYLOAD scenario. Each one should be rejected by the
 * ingestion service for a different reason and end up in the dead letter queue.
 */
final class InvalidPayloads {

    private InvalidPayloads() {
    }

    /**
     * Rotates through four kinds of broken payload. Broken messages have sequence 3, 6, 9, ...
     * (every INVALID_PAYLOAD_EVERY-th), so 3 -> kind 0, 6 -> kind 1, 9 -> kind 2, 12 -> kind 3, 15 -> kind 0.
     */
    static byte[] breakReading(TelemetryEvent reading) {
        long brokenMessageNumber = reading.sequence() / SimulatedDevice.INVALID_PAYLOAD_EVERY;
        int kind = (int) Math.floorMod(brokenMessageNumber - 1, 4);
        return switch (kind) {
            // 1. Not JSON at all
            case 0 -> ("temperature=" + reading.temperature() + ";garbage").getBytes(StandardCharsets.UTF_8);
            // 2. A required field is missing
            case 1 -> toJson(copy(reading, reading.tenantId(), null));
            // 3. A physically impossible value
            case 2 -> toJson(copy(reading, reading.tenantId(), 999.0));
            // 4. The payload claims another tenant than the MQTT topic
            default -> toJson(copy(reading, "someone-else", reading.temperature()));
        };
    }

    private static TelemetryEvent copy(TelemetryEvent reading, String tenantId, Double temperature) {
        return new TelemetryEvent(reading.deviceId(), tenantId, reading.timestamp(), reading.sequence(),
                temperature, reading.humidity(), reading.doorOpen(), reading.batteryLevel());
    }

    private static byte[] toJson(TelemetryEvent event) {
        return EventJson.mapper().writeValueAsBytes(event);
    }
}
