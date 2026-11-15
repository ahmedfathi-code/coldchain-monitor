package com.coldchain.api.notification;

import com.coldchain.common.event.AlertEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Anti-spam: at most one notification per device and alert type per cooldown period.
 * <p>
 * Uses Redis {@code SET key value NX EX seconds}: NX = only set the key if it does not exist,
 * EX = let Redis delete it automatically after the cooldown. The first caller creates the key
 * and may notify; every caller until the key expires gets "already exists" and must not.
 * Because Redis executes the command atomically, this also works with several api-service
 * instances running at the same time.
 */
@Component
public class NotificationCooldown {

    private final StringRedisTemplate redis;
    private final Duration cooldown;

    public NotificationCooldown(StringRedisTemplate redis, NotificationProperties properties) {
        this.redis = redis;
        this.cooldown = properties.cooldown();
    }

    static String keyFor(AlertEvent alert) {
        return "notification-cooldown:" + alert.deviceId() + ":" + alert.type();
    }

    /** @return true if the caller may send a notification now (and the cooldown has started) */
    public boolean tryAcquire(AlertEvent alert) {
        Boolean created = redis.opsForValue().setIfAbsent(keyFor(alert), alert.alertId(), cooldown);
        return Boolean.TRUE.equals(created);
    }

    /** Ends the cooldown early, e.g. when sending failed and should be retried. */
    public void release(AlertEvent alert) {
        redis.delete(keyFor(alert));
    }
}
