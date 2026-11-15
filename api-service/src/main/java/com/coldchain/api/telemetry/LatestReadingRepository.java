package com.coldchain.api.telemetry;

import com.coldchain.api.telemetry.TelemetryDtos.LatestReadingResponse;
import com.coldchain.common.topic.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Reads the latest-reading hash written by the stream processor's latest-state writer. */
@Repository
public class LatestReadingRepository {

    private final StringRedisTemplate redis;

    public LatestReadingRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<LatestReadingResponse> find(String deviceId) {
        Map<Object, Object> hash = redis.opsForHash().entries(RedisKeys.latestReading(deviceId));
        if (hash.isEmpty() || !hash.containsKey("timestamp")) {
            return Optional.empty();
        }
        return Optional.of(new LatestReadingResponse(
                deviceId,
                Instant.parse(field(hash, "timestamp")),
                Long.parseLong(field(hash, "sequence")),
                Double.parseDouble(field(hash, "temperature")),
                Double.parseDouble(field(hash, "humidity")),
                Boolean.parseBoolean(field(hash, "doorOpen")),
                Integer.parseInt(field(hash, "batteryLevel")),
                Instant.parse(field(hash, "lastSeenAt"))));
    }

    private static String field(Map<Object, Object> hash, String name) {
        return (String) hash.get(name);
    }
}
