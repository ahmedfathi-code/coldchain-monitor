package com.coldchain.streams.rules;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings under {@code rules.*} in application.yml. Per-device thresholds (temperature range,
 * excursion and door durations) are NOT here: they come from the device.config topic.
 *
 * @param gracePeriod          how late (in event time) a reading may arrive and still be used
 * @param offlineAfter         a device is offline when we heard nothing from it for this long
 * @param offlineCheckInterval how often the offline punctuator scans the devices
 * @param lowBatteryThreshold  battery level (%) below which LOW_BATTERY is raised
 */
@ConfigurationProperties("rules")
public record RulesProperties(
        Duration gracePeriod,
        Duration offlineAfter,
        Duration offlineCheckInterval,
        int lowBatteryThreshold) {
}
