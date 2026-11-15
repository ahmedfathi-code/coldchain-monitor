package com.coldchain.api.notification;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the SET NX EX cooldown against a real Redis (started in Docker by Testcontainers). */
@Testcontainers
class NotificationCooldownIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final NotificationCooldown cooldown = new NotificationCooldown(redis,
            new NotificationProperties(Duration.ofMinutes(30), null));

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
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void onlyTheFirstAlertOfADeviceAndTypeMayNotify() {
        AlertEvent alert = TestAlerts.opened("nile-fridge-01", AlertType.TEMP_EXCURSION);

        assertThat(cooldown.tryAcquire(alert)).isTrue();
        assertThat(cooldown.tryAcquire(alert)).isFalse();
        assertThat(cooldown.tryAcquire(alert)).isFalse();
    }

    @Test
    void theCooldownKeyExpiresAutomatically() {
        AlertEvent alert = TestAlerts.opened("nile-fridge-01", AlertType.TEMP_EXCURSION);

        cooldown.tryAcquire(alert);

        Long ttlSeconds = redis.getExpire(NotificationCooldown.keyFor(alert));
        assertThat(ttlSeconds).isBetween(29 * 60L, 30 * 60L);
    }

    @Test
    void otherDevicesAndOtherAlertTypesHaveTheirOwnCooldown() {
        assertThat(cooldown.tryAcquire(TestAlerts.opened("nile-fridge-01", AlertType.TEMP_EXCURSION))).isTrue();

        assertThat(cooldown.tryAcquire(TestAlerts.opened("nile-fridge-01", AlertType.DOOR_OPEN_TOO_LONG))).isTrue();
        assertThat(cooldown.tryAcquire(TestAlerts.opened("delta-fridge-01", AlertType.TEMP_EXCURSION))).isTrue();
    }

    @Test
    void releaseEndsTheCooldownEarly() {
        AlertEvent alert = TestAlerts.opened("nile-fridge-01", AlertType.TEMP_EXCURSION);
        cooldown.tryAcquire(alert);

        cooldown.release(alert);

        assertThat(cooldown.tryAcquire(alert)).isTrue();
    }
}
