package com.coldchain.common.event;

/**
 * Alert thresholds of one device, published by the api-service on the compacted Kafka topic
 * {@code device.config} (key = deviceId). Because the topic is compacted, Kafka keeps at
 * least the latest config of every device, so the stream processor can always rebuild the
 * full table of thresholds on startup.
 *
 * @param deviceId           device these thresholds belong to
 * @param tenantId           tenant that owns the device
 * @param minTemp            lowest allowed temperature in degrees Celsius
 * @param maxTemp            highest allowed temperature in degrees Celsius
 * @param excursionMinutes   how long the temperature may stay out of range before alerting
 * @param doorOpenMaxMinutes how long the door may stay open before alerting
 * @param active             false for a decommissioned device: it raises no alerts at all.
 *                           Missing (null) in messages written before this field existed,
 *                           which means active.
 */
public record DeviceConfigEvent(
        String deviceId,
        String tenantId,
        double minTemp,
        double maxTemp,
        int excursionMinutes,
        int doorOpenMaxMinutes,
        Boolean active) {

    public DeviceConfigEvent {
        if (active == null) {
            active = true;
        }
    }

    // Defaults used when a device has no config yet. 2-8 °C is the standard storage range
    // for refrigerated medicines (vaccines, insulin, ...).
    public static final double DEFAULT_MIN_TEMP = 2.0;
    public static final double DEFAULT_MAX_TEMP = 8.0;
    public static final int DEFAULT_EXCURSION_MINUTES = 15;
    public static final int DEFAULT_DOOR_OPEN_MAX_MINUTES = 5;

    /** Thresholds to use for a device that has no config on the {@code device.config} topic. */
    public static DeviceConfigEvent defaultsFor(String deviceId, String tenantId) {
        return new DeviceConfigEvent(
                deviceId,
                tenantId,
                DEFAULT_MIN_TEMP,
                DEFAULT_MAX_TEMP,
                DEFAULT_EXCURSION_MINUTES,
                DEFAULT_DOOR_OPEN_MAX_MINUTES,
                true);
    }
}
