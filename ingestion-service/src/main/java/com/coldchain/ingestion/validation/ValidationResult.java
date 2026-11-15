package com.coldchain.ingestion.validation;

import com.coldchain.common.event.TelemetryEvent;

/**
 * Outcome of validating one MQTT message: either a valid event, or the reason it is invalid.
 *
 * @param event  the parsed reading (only when valid)
 * @param reason category of the problem (only when invalid)
 * @param error  why the message was rejected, in detail (only when invalid)
 */
public record ValidationResult(TelemetryEvent event, RejectReason reason, String error) {

    public static ValidationResult valid(TelemetryEvent event) {
        return new ValidationResult(event, null, null);
    }

    public static ValidationResult invalid(RejectReason reason, String error) {
        return new ValidationResult(null, reason, error);
    }

    public boolean isValid() {
        return error == null;
    }
}
