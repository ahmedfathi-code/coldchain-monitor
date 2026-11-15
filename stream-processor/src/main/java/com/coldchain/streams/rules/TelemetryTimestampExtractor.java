package com.coldchain.streams.rules;

import com.coldchain.common.event.TelemetryEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Tells Kafka Streams to use the DEVICE time of a reading (event time), not the time the
 * record was written to Kafka. So "15 minutes out of range" means 15 minutes on the device,
 * even if the readings reached us late or in a burst.
 */
public class TelemetryTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() instanceof TelemetryEvent reading && reading.timestamp() != null) {
            return reading.timestamp().toEpochMilli();
        }
        // Should not happen (ingestion validates every reading); fall back to the highest
        // timestamp seen so far on this partition so stream time does not jump around.
        return partitionTime >= 0 ? partitionTime : record.timestamp();
    }
}
