package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertTransition;
import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.Severity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Small helpers shared by the rule processors. */
final class Alerts {

    private Alerts() {
    }

    static AlertEvent create(AlertType type, AlertTransition transition, Severity severity,
                             String deviceId, String tenantId, Instant detectedAt,
                             Map<String, Object> details) {
        return new AlertEvent(UUID.randomUUID().toString(), deviceId, tenantId, type, transition,
                severity, detectedAt, details);
    }

    /** Whole minutes, which is what humans want to read in an alert. */
    static long minutes(Duration duration) {
        return duration.toMinutes();
    }
}
