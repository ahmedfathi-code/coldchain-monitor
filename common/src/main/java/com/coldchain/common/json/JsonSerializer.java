package com.coldchain.common.json;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.core.JacksonException;

/**
 * Kafka {@link Serializer} that turns any object (our event records) into JSON bytes.
 *
 * @param <T> type of the Kafka record value (or key)
 */
public class JsonSerializer<T> implements Serializer<T> {

    @Override
    public byte[] serialize(String topic, T data) {
        // null stays null: on a compacted topic a null value is a "tombstone" (delete marker).
        if (data == null) {
            return null;
        }
        try {
            return EventJson.mapper().writeValueAsBytes(data);
        } catch (JacksonException e) {
            throw new SerializationException(
                    "Could not serialize " + data.getClass().getSimpleName() + " for topic " + topic, e);
        }
    }
}
