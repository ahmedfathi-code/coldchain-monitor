package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertTransition;
import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.Severity;
import com.coldchain.common.event.TelemetryEvent;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.TimestampedKeyValueStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEVICE_OFFLINE: no reading from a device for {@code offlineAfter} (5 minutes by default).
 * <p>
 * The problem: an offline device sends nothing, so there is no record that could trigger the
 * rule. The solution is a punctuator: a callback that Kafka Streams runs on a schedule. It
 * uses WALL_CLOCK_TIME (the real clock), because event time only advances when data arrives,
 * and data is exactly what is missing.
 * <p>
 * State store: deviceId -> {@link LastSeen}. Every reading updates it (and emits RECOVERED if
 * the device was offline); the punctuator scans all devices and emits OPENED for silent ones.
 * <p>
 * Inactive (decommissioned) devices are never reported offline: they are expected to be
 * silent. Their config is read from the device.config GlobalKTable's store, which every
 * processor can read.
 */
public class DeviceOfflineProcessor implements Processor<String, TelemetryEvent, String, AlertEvent> {

    private final String storeName;
    private final String deviceConfigStoreName;
    private final Duration offlineAfter;
    private final Duration checkInterval;

    private ProcessorContext<String, AlertEvent> context;
    private KeyValueStore<String, LastSeen> lastSeenStore;
    private TimestampedKeyValueStore<String, DeviceConfigEvent> deviceConfigs;

    public DeviceOfflineProcessor(String storeName, String deviceConfigStoreName, Duration offlineAfter,
                                  Duration checkInterval) {
        this.storeName = storeName;
        this.deviceConfigStoreName = deviceConfigStoreName;
        this.offlineAfter = offlineAfter;
        this.checkInterval = checkInterval;
    }

    @Override
    public void init(ProcessorContext<String, AlertEvent> context) {
        this.context = context;
        this.lastSeenStore = context.getStateStore(storeName);
        this.deviceConfigs = context.getStateStore(deviceConfigStoreName);
        context.schedule(checkInterval, PunctuationType.WALL_CLOCK_TIME, this::findOfflineDevices);
    }

    @Override
    public void process(Record<String, TelemetryEvent> record) {
        String deviceId = record.key();
        long now = context.currentSystemTimeMs();
        LastSeen previous = lastSeenStore.get(deviceId);

        if (previous != null && previous.offline()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lastSeenAt", Instant.ofEpochMilli(previous.lastSeenMillis()).toString());
            details.put("offlineMinutes", Alerts.minutes(Duration.ofMillis(now - previous.lastSeenMillis())));
            emit(deviceId, previous.tenantId(), AlertTransition.RECOVERED, now, details);
        }
        lastSeenStore.put(deviceId, new LastSeen(record.value().tenantId(), now, false));
    }

    /** The punctuator: called every checkInterval with the current wall-clock time. */
    private void findOfflineDevices(long now) {
        // Collect first, update after: we should not modify the store while iterating over it.
        List<KeyValue<String, LastSeen>> newlyOffline = new ArrayList<>();
        try (KeyValueIterator<String, LastSeen> devices = lastSeenStore.all()) {
            while (devices.hasNext()) {
                KeyValue<String, LastSeen> device = devices.next();
                LastSeen lastSeen = device.value;
                if (!lastSeen.offline() && now - lastSeen.lastSeenMillis() >= offlineAfter.toMillis()
                        && isActive(device.key)) {
                    newlyOffline.add(device);
                }
            }
        }

        for (KeyValue<String, LastSeen> device : newlyOffline) {
            LastSeen lastSeen = device.value;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lastSeenAt", Instant.ofEpochMilli(lastSeen.lastSeenMillis()).toString());
            details.put("offlineAfterMinutes", Alerts.minutes(offlineAfter));
            emit(device.key, lastSeen.tenantId(), AlertTransition.OPENED, now, details);
            lastSeenStore.put(device.key, new LastSeen(lastSeen.tenantId(), lastSeen.lastSeenMillis(), true));
        }
    }

    /** Devices without a config are monitored with default thresholds, so they count as active. */
    private boolean isActive(String deviceId) {
        ValueAndTimestamp<DeviceConfigEvent> config = deviceConfigs.get(deviceId);
        return config == null || config.value().active();
    }

    private void emit(String deviceId, String tenantId, AlertTransition transition, long now,
                      Map<String, Object> details) {
        AlertEvent alert = Alerts.create(AlertType.DEVICE_OFFLINE, transition, Severity.CRITICAL,
                deviceId, tenantId, Instant.ofEpochMilli(now), details);
        context.forward(new Record<>(deviceId, alert, now));
    }
}
