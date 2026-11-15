package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertTransition;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.TelemetryEvent;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State machine for a {@link SustainedConditionRule}, one instance of state per device:
 * <pre>
 *   NORMAL --condition starts--> PENDING --held for minimumDuration--> OPEN (emit OPENED once)
 *     ^                             |                                    |
 *     +-------condition ends--------+----------condition ends------------+ (emit RECOVERED if OPEN)
 * </pre>
 * NORMAL = no entry in the store, PENDING = entry with alertOpen=false, OPEN = alertOpen=true.
 * All times are device event times, so the result does not depend on when data arrives.
 * Readings of an inactive device raise nothing and reset the state to NORMAL.
 */
public class SustainedConditionProcessor implements Processor<String, ReadingWithConfig, String, AlertEvent> {

    private final SustainedConditionRule rule;
    private final String storeName;

    private ProcessorContext<String, AlertEvent> context;
    private KeyValueStore<String, ConditionState> states;

    public SustainedConditionProcessor(SustainedConditionRule rule, String storeName) {
        this.rule = rule;
        this.storeName = storeName;
    }

    @Override
    public void init(ProcessorContext<String, AlertEvent> context) {
        this.context = context;
        this.states = context.getStateStore(storeName);
    }

    @Override
    public void process(Record<String, ReadingWithConfig> record) {
        String deviceId = record.key();
        TelemetryEvent reading = record.value().reading();
        DeviceConfigEvent config = record.value().config();
        Instant now = reading.timestamp();
        ConditionState state = states.get(deviceId);

        if (!config.active()) {
            // Decommissioned device: no alerts. Forget a condition in progress, so that after
            // re-activation it is not counted from this old start time.
            states.delete(deviceId);
            return;
        }
        if (rule.isConditionMet(reading, config)) {
            // The condition started now, or continues since an earlier reading.
            Instant since = state == null ? now : state.since();
            boolean alertOpen = state != null && state.alertOpen();
            Duration lasted = Duration.between(since, now);

            if (!alertOpen && lasted.compareTo(rule.minimumDuration(config)) >= 0) {
                emit(AlertTransition.OPENED, reading, config, since, lasted);
                alertOpen = true;
            }
            states.put(deviceId, new ConditionState(since, alertOpen));
        } else if (state != null) {
            // The condition is over. Only tell the world if we had raised an alert.
            if (state.alertOpen()) {
                emit(AlertTransition.RECOVERED, reading, config, state.since(), Duration.between(state.since(), now));
            }
            states.delete(deviceId);
        }
    }

    private void emit(AlertTransition transition, TelemetryEvent reading, DeviceConfigEvent config,
                      Instant since, Duration lasted) {
        Map<String, Object> details = new LinkedHashMap<>(rule.details(reading, config));
        details.put("since", since.toString());
        details.put("durationMinutes", Alerts.minutes(lasted));

        AlertEvent alert = Alerts.create(rule.type(), transition, rule.severity(),
                reading.deviceId(), reading.tenantId(), reading.timestamp(), details);
        context.forward(new Record<>(reading.deviceId(), alert, reading.timestamp().toEpochMilli()));
    }
}
