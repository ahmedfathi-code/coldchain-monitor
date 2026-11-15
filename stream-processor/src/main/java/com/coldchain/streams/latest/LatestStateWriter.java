package com.coldchain.streams.latest;

import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.common.topic.KafkaTopics;
import com.coldchain.common.topic.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Keeps the latest reading of every device in Redis, so the API can answer
 * "what is the temperature right now?" without querying the full history.
 * <p>
 * One hash per device ({@code device:{deviceId}:latest}) with the reading's fields, plus
 * {@code timestampMillis} (event time, used to ignore late readings) and {@code lastSeenAt}
 * (when we last received anything from the device).
 * <p>
 * This is a plain Kafka consumer, separate from the Kafka Streams topology.
 */
@Component
public class LatestStateWriter {

    public static final String CONSUMER_GROUP = "latest-state-writer";

    private static final Logger log = LoggerFactory.getLogger(LatestStateWriter.class);

    private static final RedisScript<Long> SAVE_IF_NEWER =
            RedisScript.of(new ClassPathResource("redis/save-latest-if-newer.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public LatestStateWriter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Redis key of a device's latest reading (the format is shared with the api-service). */
    public static String keyFor(String deviceId) {
        return RedisKeys.latestReading(deviceId);
    }

    @KafkaListener(topics = KafkaTopics.TELEMETRY_RAW, groupId = CONSUMER_GROUP)
    public void onReading(TelemetryEvent reading) {
        boolean saved = save(reading);
        if (!saved) {
            log.debug("Ignored late reading {} of {}", reading.sequence(), reading.deviceId());
        }
    }

    /** @return true if saved, false if Redis already holds a newer reading of this device */
    boolean save(TelemetryEvent reading) {
        Long result = redis.execute(SAVE_IF_NEWER,
                List.of(keyFor(reading.deviceId())),
                String.valueOf(reading.timestamp().toEpochMilli()),
                clock.instant().toString(),
                "deviceId", reading.deviceId(),
                "tenantId", reading.tenantId(),
                "timestamp", reading.timestamp().toString(),
                "sequence", String.valueOf(reading.sequence()),
                "temperature", String.valueOf(reading.temperature()),
                "humidity", String.valueOf(reading.humidity()),
                "doorOpen", String.valueOf(reading.doorOpen()),
                "batteryLevel", String.valueOf(reading.batteryLevel()));
        return Long.valueOf(1).equals(result);
    }
}
