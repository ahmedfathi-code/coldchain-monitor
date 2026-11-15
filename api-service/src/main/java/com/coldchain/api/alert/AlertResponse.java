package com.coldchain.api.alert;

import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.Severity;
import com.coldchain.common.json.EventJson;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.Map;

/** An alert as returned by the API. */
public record AlertResponse(String id, String deviceId, AlertType type, Severity severity, AlertStatus status,
                            Map<String, Object> details, Instant openedAt,
                            String acknowledgedBy, Instant acknowledgedAt,
                            String resolvedBy, Instant resolvedAt) {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    static AlertResponse from(Alert alert) {
        Map<String, Object> details = alert.getDetails() == null
                ? Map.of()
                : EventJson.mapper().readValue(alert.getDetails(), MAP);
        return new AlertResponse(alert.getId(), alert.getDeviceId(), alert.getType(), alert.getSeverity(),
                alert.getStatus(), details, alert.getOpenedAt(), alert.getAcknowledgedBy(),
                alert.getAcknowledgedAt(), alert.getResolvedBy(), alert.getResolvedAt());
    }
}
