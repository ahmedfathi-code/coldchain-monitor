package com.coldchain.api.notification;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.Severity;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/** Turns an alert into a short, human-readable chat message. */
@Component
public class AlertMessageFormatter {

    public String format(AlertEvent alert) {
        String icon = alert.severity() == Severity.CRITICAL ? "🚨" : "⚠️";
        String details = alert.details() == null ? "" : alert.details().entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("\n"));
        return icon + " " + alert.severity() + " - " + alert.type() + "\n"
                + "Device: " + alert.deviceId() + " (" + alert.tenantId() + ")\n"
                + "Detected at: " + alert.detectedAt() + "\n"
                + details;
    }
}
