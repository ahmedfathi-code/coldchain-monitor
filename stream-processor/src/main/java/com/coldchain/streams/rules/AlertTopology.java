package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.common.json.JsonSerdes;
import com.coldchain.common.topic.KafkaTopics;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

/**
 * Builds the Kafka Streams topology of the alert rules:
 * <pre>
 *   telemetry.raw (event time)
 *     ├─> DeviceOfflineProcessor (punctuator, reads device.config) ──────────┐
 *     └─> LateReadingFilter ─> join device.config (GlobalKTable, defaults) ─┤
 *           ├─> TEMP_EXCURSION      (SustainedConditionProcessor)            ├─> alerts
 *           ├─> DOOR_OPEN_TOO_LONG  (SustainedConditionProcessor)            │
 *           └─> LOW_BATTERY         (SustainedConditionProcessor) ───────────┘
 * </pre>
 * Devices whose config says {@code active=false} (decommissioned) raise no alerts.
 * A plain static method (no Spring) so tests can run it in TopologyTestDriver.
 */
public final class AlertTopology {

    public static final String DEVICE_CONFIG_STORE = "device-config-store";
    public static final String NEWEST_EVENT_TIME_STORE = "newest-event-time-store";
    public static final String TEMP_EXCURSION_STORE = "temp-excursion-store";
    public static final String DOOR_OPEN_STORE = "door-open-store";
    public static final String LOW_BATTERY_STORE = "low-battery-store";
    public static final String LAST_SEEN_STORE = "last-seen-store";

    private AlertTopology() {
    }

    public static KStream<String, AlertEvent> build(StreamsBuilder builder, RulesProperties rules) {
        // Thresholds of every device. A GlobalKTable is fully loaded on EVERY instance before
        // processing starts, so a join never misses a config and needs no co-partitioning.
        GlobalKTable<String, DeviceConfigEvent> deviceConfigs = builder.globalTable(
                KafkaTopics.DEVICE_CONFIG,
                Consumed.with(Serdes.String(), JsonSerdes.deviceConfig()),
                Materialized.<String, DeviceConfigEvent>as(Stores.inMemoryKeyValueStore(DEVICE_CONFIG_STORE))
                        .withKeySerde(Serdes.String())
                        .withValueSerde(JsonSerdes.deviceConfig()));

        KStream<String, TelemetryEvent> telemetry = builder.stream(
                KafkaTopics.TELEMETRY_RAW,
                Consumed.with(Serdes.String(), JsonSerdes.telemetry())
                        .withTimestampExtractor(new TelemetryTimestampExtractor()));

        // State stores: in-memory, and backed up to a changelog topic by Kafka Streams, so
        // they are rebuilt after a restart. The state is tiny (one entry per device).
        builder.addStateStore(keyValueStore(NEWEST_EVENT_TIME_STORE, Serdes.Long()));
        builder.addStateStore(keyValueStore(TEMP_EXCURSION_STORE, JsonSerdes.of(ConditionState.class)));
        builder.addStateStore(keyValueStore(DOOR_OPEN_STORE, JsonSerdes.of(ConditionState.class)));
        builder.addStateStore(keyValueStore(LOW_BATTERY_STORE, JsonSerdes.of(ConditionState.class)));
        builder.addStateStore(keyValueStore(LAST_SEEN_STORE, JsonSerdes.of(LastSeen.class)));

        KStream<String, ReadingWithConfig> readings = telemetry
                .process(() -> new LateReadingFilter(NEWEST_EVENT_TIME_STORE, rules.gracePeriod()),
                        NEWEST_EVENT_TIME_STORE)
                .leftJoin(deviceConfigs,
                        (deviceId, reading) -> deviceId,
                        (reading, config) -> new ReadingWithConfig(reading,
                                config != null ? config : DeviceConfigEvent.defaultsFor(reading.deviceId(), reading.tenantId())));

        KStream<String, AlertEvent> temperatureAlerts = readings.process(
                () -> new SustainedConditionProcessor(new TemperatureExcursionRule(), TEMP_EXCURSION_STORE),
                TEMP_EXCURSION_STORE);
        KStream<String, AlertEvent> doorAlerts = readings.process(
                () -> new SustainedConditionProcessor(new DoorOpenTooLongRule(), DOOR_OPEN_STORE),
                DOOR_OPEN_STORE);
        KStream<String, AlertEvent> batteryAlerts = readings.process(
                () -> new SustainedConditionProcessor(new LowBatteryRule(rules.lowBatteryThreshold()), LOW_BATTERY_STORE),
                LOW_BATTERY_STORE);
        // Offline detection looks at every reading, even late ones: any reading proves the
        // device is alive.
        KStream<String, AlertEvent> offlineAlerts = telemetry.process(
                () -> new DeviceOfflineProcessor(LAST_SEEN_STORE, DEVICE_CONFIG_STORE, rules.offlineAfter(),
                        rules.offlineCheckInterval()),
                LAST_SEEN_STORE);

        KStream<String, AlertEvent> alerts = temperatureAlerts
                .merge(doorAlerts)
                .merge(batteryAlerts)
                .merge(offlineAlerts);
        alerts.to(KafkaTopics.ALERTS, Produced.with(Serdes.String(), JsonSerdes.alert()));
        return alerts;
    }

    private static <V> StoreBuilder<KeyValueStore<String, V>> keyValueStore(
            String name, Serde<V> valueSerde) {
        return Stores.keyValueStoreBuilder(Stores.inMemoryKeyValueStore(name), Serdes.String(), valueSerde);
    }
}
