package com.coldchain.api.telemetry;

import java.time.Instant;

/** Response bodies of the telemetry, statistics and report endpoints. */
public final class TelemetryDtos {

    private TelemetryDtos() {
    }

    /** The latest reading of a device, from Redis. */
    public record LatestReadingResponse(String deviceId, Instant timestamp, long sequence, double temperature,
                                        double humidity, boolean doorOpen, int batteryLevel, Instant lastSeenAt) {
    }

    /** One stored reading, from ClickHouse. */
    public record TelemetryPoint(Instant timestamp, long sequence, double temperature, double humidity,
                                 boolean doorOpen, int batteryLevel) {
    }

    /** Temperature statistics of one hour, from the pre-aggregated ClickHouse table. */
    public record HourlyStat(Instant hour, double minTemperature, double maxTemperature,
                             double avgTemperature, long readings) {
    }

    /**
     * Compliance of one device over a period: did the medicines stay in range?
     *
     * @param readings           number of readings in the period
     * @param monitoredMinutes   time covered by readings (gaps longer than 5 minutes are NOT
     *                           counted: we do not know what happened while the device was silent)
     * @param minutesOutOfRange  monitored time with the temperature outside [minTemp, maxTemp]
     * @param excursions         number of separate periods with the temperature out of range
     * @param compliancePercent  share of the monitored time in range (null without data)
     */
    public record ComplianceReport(String deviceId, Instant from, Instant to, double minTemp, double maxTemp,
                                   long readings, double monitoredMinutes, double minutesOutOfRange,
                                   long excursions, Double minTemperature, Double maxTemperature,
                                   Double avgTemperature, Double compliancePercent) {
    }
}
