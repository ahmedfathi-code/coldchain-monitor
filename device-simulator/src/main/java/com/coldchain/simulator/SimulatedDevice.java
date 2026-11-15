package com.coldchain.simulator;

import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.simulator.SimulatorProperties.DeviceSpec;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Random;

/**
 * One fake device. It remembers its own state (sequence number, current temperature, ...)
 * so every new reading continues smoothly from the previous one, like a real sensor.
 * Scenarios are driven by the time elapsed since the device's first reading.
 */
public class SimulatedDevice {

    // A healthy medicine fridge is regulated around the middle of the 2-8 °C range.
    static final double TARGET_TEMPERATURE = 5.0;
    static final double TARGET_HUMIDITY = 50.0;
    // Normal readings stay safely inside 2-8 °C so they never trigger an alert by accident.
    static final double NORMAL_MIN_TEMPERATURE = 2.5;
    static final double NORMAL_MAX_TEMPERATURE = 7.5;

    // Scenario timings (see Scenario).
    static final Duration INCIDENT_STARTS_AFTER = Duration.ofMinutes(1);
    static final Duration POWER_OUTAGE_DURATION = Duration.ofMinutes(6);
    static final double OUTAGE_WARMING_PER_MINUTE = 1.0;
    static final double OUTAGE_MAX_TEMPERATURE = 15.0;
    static final Duration DOOR_OPEN_DURATION = Duration.ofMinutes(3);
    static final double DOOR_WARMING_PER_MINUTE = 0.3;
    static final Duration OFFLINE_DURATION = Duration.ofMinutes(7);
    static final int LOW_BATTERY_START_LEVEL = 20;
    static final double BATTERY_DRAIN_PER_MINUTE = 2.0;
    static final Duration BATTERY_REPLACED_AFTER = Duration.ofMinutes(6);
    static final int INVALID_PAYLOAD_EVERY = 3;

    private final DeviceSpec spec;
    private final Random random;

    private Instant startedAt;
    private Instant lastReadingAt;
    private long sequence = 0;
    private double temperature = TARGET_TEMPERATURE;
    private double humidity = TARGET_HUMIDITY;
    private boolean doorOpen = false;
    private int batteryLevel;

    public SimulatedDevice(DeviceSpec spec, Random random) {
        this.spec = spec;
        this.random = random;
        // Every device starts with a slightly different, healthy battery level (90-100 %).
        this.batteryLevel = spec.scenario() == Scenario.LOW_BATTERY
                ? LOW_BATTERY_START_LEVEL
                : 90 + random.nextInt(11);
    }

    public DeviceSpec spec() {
        return spec;
    }

    /**
     * Produces the next reading of this device, taken at the given time.
     *
     * @return the reading, or empty while the device is offline (it sends nothing)
     */
    public Optional<TelemetryEvent> nextReading(Instant now) {
        if (startedAt == null) {
            startedAt = now;
            lastReadingAt = now;
        }
        Duration elapsed = Duration.between(startedAt, now);
        double minutesSinceLastStep = Duration.between(lastReadingAt, now).toMillis() / 60_000.0;
        lastReadingAt = now;

        switch (spec.scenario()) {
            case NORMAL, INVALID_PAYLOAD -> normalStep();
            case POWER_OUTAGE -> powerOutageStep(elapsed, minutesSinceLastStep);
            case DOOR_LEFT_OPEN -> doorLeftOpenStep(elapsed, minutesSinceLastStep);
            case DEVICE_OFFLINE -> {
                if (isDuringIncident(elapsed, OFFLINE_DURATION)) {
                    return Optional.empty();
                }
                normalStep();
            }
            case LOW_BATTERY -> {
                normalStep();
                lowBatteryStep(elapsed);
            }
        }

        sequence++;
        return Optional.of(new TelemetryEvent(
                spec.deviceId(),
                spec.tenantId(),
                now.truncatedTo(ChronoUnit.MILLIS),
                sequence,
                round2(temperature),
                round2(humidity),
                doorOpen,
                batteryLevel));
    }

    /** True if the reading just produced should be replaced by a broken message. */
    public boolean sendsInvalidPayload() {
        return spec.scenario() == Scenario.INVALID_PAYLOAD && sequence % INVALID_PAYLOAD_EVERY == 0;
    }

    private void normalStep() {
        // Like a thermostat: each step pulls the value 10 % back towards the target, then adds
        // a little random noise. The result wanders realistically but stays near the target.
        boolean wasInNormalBand = temperature >= NORMAL_MIN_TEMPERATURE && temperature <= NORMAL_MAX_TEMPERATURE;
        temperature = drift(temperature, TARGET_TEMPERATURE, 0.15);
        if (wasInNormalBand) {
            temperature = Math.clamp(temperature, NORMAL_MIN_TEMPERATURE, NORMAL_MAX_TEMPERATURE);
        }
        // Otherwise the fridge is still cooling down after an incident: no clamping, so the
        // temperature comes back gradually.
        humidity = Math.clamp(drift(humidity, TARGET_HUMIDITY, 1.0), 0.0, 100.0);
    }

    private void powerOutageStep(Duration elapsed, double minutes) {
        if (isDuringIncident(elapsed, POWER_OUTAGE_DURATION)) {
            // No cooling: the fridge slowly warms up towards room temperature.
            temperature = Math.min(OUTAGE_MAX_TEMPERATURE,
                    temperature + OUTAGE_WARMING_PER_MINUTE * minutes + random.nextGaussian() * 0.05);
        } else {
            normalStep();
        }
    }

    private void doorLeftOpenStep(Duration elapsed, double minutes) {
        doorOpen = isDuringIncident(elapsed, DOOR_OPEN_DURATION);
        if (doorOpen) {
            temperature = Math.min(NORMAL_MAX_TEMPERATURE, temperature + DOOR_WARMING_PER_MINUTE * minutes);
        } else {
            normalStep();
        }
    }

    private void lowBatteryStep(Duration elapsed) {
        if (elapsed.compareTo(BATTERY_REPLACED_AFTER) >= 0) {
            batteryLevel = 100;
        } else {
            double minutes = elapsed.toMillis() / 60_000.0;
            batteryLevel = Math.max(0, (int) (LOW_BATTERY_START_LEVEL - BATTERY_DRAIN_PER_MINUTE * minutes));
        }
    }

    /** True between INCIDENT_STARTS_AFTER and INCIDENT_STARTS_AFTER + duration. */
    private static boolean isDuringIncident(Duration elapsed, Duration duration) {
        return elapsed.compareTo(INCIDENT_STARTS_AFTER) >= 0
                && elapsed.compareTo(INCIDENT_STARTS_AFTER.plus(duration)) < 0;
    }

    private double drift(double value, double target, double noise) {
        return value + (target - value) * 0.1 + random.nextGaussian() * noise;
    }

    /** Real sensors have limited precision; 2 decimals also keeps the JSON readable. */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
