package com.coldchain.api.notification;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertTransition;
import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.Severity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Alert events for tests. */
final class TestAlerts {

    private TestAlerts() {
    }

    static AlertEvent opened(String deviceId, AlertType type) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("temperature", 9.4);
        details.put("maxTemp", 8.0);
        details.put("durationMinutes", 15);
        return new AlertEvent("alert-1", deviceId, "nile-pharmacy", type, AlertTransition.OPENED,
                Severity.CRITICAL, Instant.parse("2026-01-15T10:15:00Z"), details);
    }
}
