package com.coldchain.common.json;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.TelemetryEvent;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;

/**
 * Ready-made {@link Serde}s (a serializer + deserializer pair) for our events.
 * Kafka Streams needs Serdes; plain producers/consumers can use the serializer or
 * deserializer half.
 */
public final class JsonSerdes {

    private JsonSerdes() {
        // factory methods only
    }

    public static <T> Serde<T> of(Class<T> type) {
        return Serdes.serdeFrom(new JsonSerializer<>(), new JsonDeserializer<>(type));
    }

    public static Serde<TelemetryEvent> telemetry() {
        return of(TelemetryEvent.class);
    }

    public static Serde<AlertEvent> alert() {
        return of(AlertEvent.class);
    }

    public static Serde<DeviceConfigEvent> deviceConfig() {
        return of(DeviceConfigEvent.class);
    }
}
