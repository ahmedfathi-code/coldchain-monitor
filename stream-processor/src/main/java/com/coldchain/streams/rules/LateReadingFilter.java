package com.coldchain.streams.rules;

import com.coldchain.common.event.TelemetryEvent;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Applies the grace period for late data, per device.
 * <p>
 * Readings of one device normally arrive in order (same Kafka partition), but a redelivered
 * or buffered reading can arrive after newer ones. A reading that is at most
 * {@code gracePeriod} older than the newest reading of its device is still used; an older
 * one is dropped, because the rules have already moved on past that moment.
 * <p>
 * State store: deviceId -> newest event time seen (epoch millis).
 */
public class LateReadingFilter implements Processor<String, TelemetryEvent, String, TelemetryEvent> {

    private static final Logger log = LoggerFactory.getLogger(LateReadingFilter.class);

    private final String storeName;
    private final long gracePeriodMillis;

    private ProcessorContext<String, TelemetryEvent> context;
    private KeyValueStore<String, Long> newestEventTime;

    public LateReadingFilter(String storeName, Duration gracePeriod) {
        this.storeName = storeName;
        this.gracePeriodMillis = gracePeriod.toMillis();
    }

    @Override
    public void init(ProcessorContext<String, TelemetryEvent> context) {
        this.context = context;
        this.newestEventTime = context.getStateStore(storeName);
    }

    @Override
    public void process(Record<String, TelemetryEvent> record) {
        // record.timestamp() is the device event time (see TelemetryTimestampExtractor).
        long eventTime = record.timestamp();
        Long newest = newestEventTime.get(record.key());

        if (newest != null && eventTime < newest - gracePeriodMillis) {
            log.warn("Dropping reading {} of {}: {} ms older than the newest one (grace period {} ms)",
                    record.value().sequence(), record.key(), newest - eventTime, gracePeriodMillis);
            return;
        }
        if (newest == null || eventTime > newest) {
            newestEventTime.put(record.key(), eventTime);
        }
        context.forward(record);
    }
}
