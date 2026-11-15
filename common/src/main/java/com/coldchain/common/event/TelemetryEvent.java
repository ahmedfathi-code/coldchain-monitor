package com.coldchain.common.event;

import java.time.Instant;

/**
 * One reading sent by a device (fridge or truck sensor).
 * <p>
 * This is the payload published on MQTT and, after validation, on the Kafka topic
 * {@code telemetry.raw}. Its JSON field names are part of the public contract, so do not
 * rename them without updating every producer and consumer (including ClickHouse).
 * <p>
 * Numeric and boolean fields use wrapper types ({@code Double}, {@code Boolean}, ...) on
 * purpose: if a device forgets a field, it becomes {@code null} and the ingestion service
 * can reject the message. With primitives, a missing temperature would silently become 0.0.
 *
 * @param deviceId     device that produced the reading
 * @param tenantId     tenant (pharmacy / transport company) that owns the device
 * @param timestamp    when the reading was taken on the device (event time, ISO-8601 in JSON)
 * @param sequence     counter incremented by the device for every message
 * @param temperature  temperature in degrees Celsius
 * @param humidity     relative humidity in percent
 * @param doorOpen     true when the fridge / truck door is open
 * @param batteryLevel battery level in percent (0-100)
 */
public record TelemetryEvent(
        String deviceId,
        String tenantId,
        Instant timestamp,
        Long sequence,
        Double temperature,
        Double humidity,
        Boolean doorOpen,
        Integer batteryLevel) {
}
