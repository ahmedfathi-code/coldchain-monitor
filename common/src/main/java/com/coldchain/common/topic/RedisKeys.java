package com.coldchain.common.topic;

/**
 * Redis key names shared by the stream processor (writes) and the api-service (reads).
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** Hash with the latest reading of a device (fields: temperature, humidity, ..., lastSeenAt). */
    public static String latestReading(String deviceId) {
        return "device:" + deviceId + ":latest";
    }
}
