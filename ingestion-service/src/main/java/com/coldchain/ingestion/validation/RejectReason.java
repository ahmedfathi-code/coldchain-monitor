package com.coldchain.ingestion.validation;

/**
 * Why a message was rejected, as a small fixed set. The detailed text goes to the DLQ header;
 * this category is used as a metric label (a label must not contain free text, or every
 * distinct message would create a new time series).
 */
public enum RejectReason {
    /** The MQTT topic is not coldchain/{tenantId}/devices/{deviceId}/telemetry. */
    WRONG_TOPIC,
    /** Not JSON, the wrong JSON types, or an empty payload. */
    INVALID_JSON,
    /** Required fields are missing. */
    MISSING_FIELDS,
    /** Values out of range, a future timestamp, or ids that do not match the topic. */
    INVALID_VALUES
}
