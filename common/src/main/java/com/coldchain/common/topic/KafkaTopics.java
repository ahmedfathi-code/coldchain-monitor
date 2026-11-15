package com.coldchain.common.topic;

/**
 * Names of the Kafka topics shared between services.
 * The topics themselves are created by infra/kafka/create-topics.sh.
 */
public final class KafkaTopics {

    /** Valid telemetry readings. Key = deviceId, so all readings of a device stay in order. */
    public static final String TELEMETRY_RAW = "telemetry.raw";

    /** Dead letter queue: messages that failed validation, with the reason in headers. */
    public static final String TELEMETRY_DLQ = "telemetry.dlq";

    /** Alert OPENED / RECOVERED events produced by the stream processor. Key = deviceId. */
    public static final String ALERTS = "alerts";

    /** Device thresholds (compacted topic). Key = deviceId, latest value wins. */
    public static final String DEVICE_CONFIG = "device.config";

    private KafkaTopics() {
        // constants only
    }
}
