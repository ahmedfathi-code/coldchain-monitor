package com.coldchain.common.json;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import tools.jackson.core.JacksonException;

/**
 * Kafka {@link Deserializer} that turns JSON bytes back into an object of a fixed type.
 * The target type is given in the constructor because Java generics are erased at runtime.
 *
 * @param <T> type of the Kafka record value (or key)
 */
public class JsonDeserializer<T> implements Deserializer<T> {

    private final Class<T> targetType;

    public JsonDeserializer(Class<T> targetType) {
        this.targetType = targetType;
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        // A null value is a tombstone on compacted topics: keep it null.
        if (data == null) {
            return null;
        }
        try {
            return EventJson.mapper().readValue(data, targetType);
        } catch (JacksonException e) {
            // Kafka's own exception type, so Kafka clients and Kafka Streams can apply their
            // standard error handling for "poison pill" records.
            throw new SerializationException(
                    "Could not deserialize " + targetType.getSimpleName() + " from topic " + topic, e);
        }
    }
}
