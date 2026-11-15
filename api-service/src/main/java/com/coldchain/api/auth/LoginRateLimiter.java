package com.coldchain.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Slows down password guessing, with counters in Redis (shared by every api-service instance).
 * Only FAILED attempts count, so normal users are never throttled:
 * <ul>
 *     <li>per IP address + username: stops guessing one account's password;</li>
 *     <li>per IP address: stops one client trying many accounts (credential stuffing).</li>
 * </ul>
 * Each counter expires {@code window} after its first failure (fixed window). When a limit is
 * reached, requests are refused with 429 until the counter expires. A successful login clears
 * the IP + username counter.
 * <p>
 * The IP is the TCP peer address. Behind a reverse proxy, configure Spring's
 * {@code server.forward-headers-strategy} so it becomes the real client IP; never trust an
 * X-Forwarded-For header sent directly by clients (anyone can fake it).
 */
@Component
public class LoginRateLimiter {

    /**
     * Settings under {@code security.login-rate-limit.*}.
     *
     * @param maxFailuresPerUser failed attempts allowed per IP + username in one window
     * @param maxFailuresPerIp   failed attempts allowed per IP in one window
     * @param window             how long the counters live
     */
    @ConfigurationProperties("security.login-rate-limit")
    public record Settings(int maxFailuresPerUser, int maxFailuresPerIp, Duration window) {
    }

    private static final RedisScript<Long> INCREMENT_WITH_TTL =
            RedisScript.of(new ClassPathResource("redis/increment-with-ttl.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final Settings settings;

    public LoginRateLimiter(StringRedisTemplate redis, Settings settings) {
        this.redis = redis;
        this.settings = settings;
    }

    /**
     * Refuses the attempt if this IP (and username, when known) failed too often.
     *
     * @param username null for requests without a username (token refresh)
     */
    public void checkAllowed(String ip, String username) {
        refuseIfAtLimit(ipKey(ip), settings.maxFailuresPerIp());
        if (username != null) {
            refuseIfAtLimit(userKey(ip, username), settings.maxFailuresPerUser());
        }
    }

    public void recordFailure(String ip, String username) {
        increment(ipKey(ip));
        if (username != null) {
            increment(userKey(ip, username));
        }
    }

    /** A successful login forgets this user's earlier failures from this IP. */
    public void recordSuccess(String ip, String username) {
        redis.delete(userKey(ip, username));
    }

    private void refuseIfAtLimit(String key, int limit) {
        String value = redis.opsForValue().get(key);
        if (value != null && Long.parseLong(value) >= limit) {
            Long secondsLeft = redis.getExpire(key);
            Duration retryAfter = Duration.ofSeconds(secondsLeft == null || secondsLeft < 1 ? 1 : secondsLeft);
            throw new TooManyRequestsException("Too many failed attempts; try again later", retryAfter);
        }
    }

    private void increment(String key) {
        redis.execute(INCREMENT_WITH_TTL, List.of(key), String.valueOf(settings.window().toMillis()));
    }

    private static String ipKey(String ip) {
        return "login-failures:ip:" + ip;
    }

    /** Lowercase: MySQL compares usernames case-insensitively, so "Admin@" is the same user. */
    private static String userKey(String ip, String username) {
        return "login-failures:user:" + ip + ":" + username.toLowerCase(Locale.ROOT);
    }
}
