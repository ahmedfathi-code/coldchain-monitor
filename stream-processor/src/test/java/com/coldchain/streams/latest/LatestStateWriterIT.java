package com.coldchain.streams.latest;

import com.coldchain.common.event.TelemetryEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the latest-state writer against a real Redis (started in Docker by Testcontainers),
 * because the important logic lives in the Lua script, which only Redis can execute.
 */
@Testcontainers
class LatestStateWriterIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    private static final Instant RECEIVED_AT = Instant.parse("2026-01-15T10:00:30Z");
    private static final String KEY = LatestStateWriter.keyFor("nile-fridge-01");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final LatestStateWriter writer = new LatestStateWriter(redis, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void emptyRedis() {
        redis.delete(KEY);
    }

    @Test
    void savesEveryFieldOfTheReadingInOneHash() {
        boolean saved = writer.save(reading(5, "2026-01-15T10:00:00Z", 4.5));

        assertThat(saved).isTrue();
        Map<Object, Object> hash = redis.opsForHash().entries(KEY);
        assertThat(hash).containsExactlyInAnyOrderEntriesOf(Map.of(
                "deviceId", "nile-fridge-01",
                "tenantId", "nile-pharmacy",
                "timestamp", "2026-01-15T10:00:00Z",
                "timestampMillis", String.valueOf(Instant.parse("2026-01-15T10:00:00Z").toEpochMilli()),
                "sequence", "5",
                "temperature", "4.5",
                "humidity", "50.0",
                "doorOpen", "false",
                "batteryLevel", "90",
                "lastSeenAt", "2026-01-15T10:00:30Z"));
    }

    @Test
    void newerReadingReplacesTheStoredOne() {
        writer.save(reading(5, "2026-01-15T10:00:00Z", 4.5));

        boolean saved = writer.save(reading(6, "2026-01-15T10:00:05Z", 4.7));

        assertThat(saved).isTrue();
        assertThat(redis.opsForHash().get(KEY, "sequence")).isEqualTo("6");
        assertThat(redis.opsForHash().get(KEY, "temperature")).isEqualTo("4.7");
    }

    @Test
    void lateReadingDoesNotOverwriteANewerOneButStillUpdatesLastSeen() {
        writer.save(reading(6, "2026-01-15T10:00:05Z", 4.7));
        Instant later = Instant.parse("2026-01-15T10:01:00Z");
        var writerLater = new LatestStateWriter(redis, Clock.fixed(later, ZoneOffset.UTC));

        boolean saved = writerLater.save(reading(5, "2026-01-15T10:00:00Z", 4.5));

        assertThat(saved).isFalse();
        assertThat(redis.opsForHash().get(KEY, "sequence")).isEqualTo("6");
        assertThat(redis.opsForHash().get(KEY, "temperature")).isEqualTo("4.7");
        assertThat(redis.opsForHash().get(KEY, "lastSeenAt")).isEqualTo(later.toString());
    }

    private static TelemetryEvent reading(long sequence, String timestamp, double temperature) {
        return new TelemetryEvent("nile-fridge-01", "nile-pharmacy", Instant.parse(timestamp),
                sequence, temperature, 50.0, false, 90);
    }
}
