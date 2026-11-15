package com.coldchain.common.event;

import java.time.Instant;
import java.util.Map;

/**
 * Published by the stream processor on the Kafka topic {@code alerts} when an alert
 * condition starts ({@link AlertTransition#OPENED}) or ends ({@link AlertTransition#RECOVERED}).
 * The api-service consumes it to store the alert and notify people.
 *
 * @param alertId    unique id of this event (a UUID string)
 * @param deviceId   device the alert is about
 * @param tenantId   tenant that owns the device
 * @param type       which rule fired
 * @param transition OPENED or RECOVERED
 * @param severity   how urgent it is
 * @param detectedAt event time at which the condition was detected
 * @param details    extra facts for humans, e.g. {"temperature": 9.4, "maxTemp": 8.0, "durationMinutes": 15}
 */
public record AlertEvent(
        String alertId,
        String deviceId,
        String tenantId,
        AlertType type,
        AlertTransition transition,
        Severity severity,
        Instant detectedAt,
        Map<String, Object> details) {
}
