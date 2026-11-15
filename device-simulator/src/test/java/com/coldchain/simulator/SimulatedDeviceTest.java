package com.coldchain.simulator;

import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.simulator.SimulatorProperties.DeviceSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedDeviceTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00.123456Z");
    private static final Duration STEP = Duration.ofSeconds(5);

    /** One simulated publish: when it happened and what the device produced (empty = offline). */
    private record Step(Duration elapsed, Optional<TelemetryEvent> reading) {
        double temperature() {
            return reading.orElseThrow().temperature();
        }
    }

    @Test
    void readingCarriesTheDeviceIdsAndMillisecondTimestamp() {
        TelemetryEvent reading = device(Scenario.NORMAL).nextReading(T0).orElseThrow();

        assertThat(reading.deviceId()).isEqualTo("nile-fridge-01");
        assertThat(reading.tenantId()).isEqualTo("nile-pharmacy");
        assertThat(reading.timestamp()).isEqualTo(Instant.parse("2026-01-15T10:00:00.123Z"));
        assertThat(reading.doorOpen()).isFalse();
        assertThat(reading.batteryLevel()).isBetween(90, 100);
    }

    @Test
    void sequenceIncreasesByOneForEveryReading() {
        SimulatedDevice device = device(Scenario.NORMAL);

        assertThat(device.nextReading(T0).orElseThrow().sequence()).isEqualTo(1);
        assertThat(device.nextReading(T0.plus(STEP)).orElseThrow().sequence()).isEqualTo(2);
        assertThat(device.nextReading(T0.plus(STEP.multipliedBy(2))).orElseThrow().sequence()).isEqualTo(3);
    }

    @Test
    void normalScenarioStaysInsideTheSafeRangeButIsNotConstant() {
        List<Step> steps = run(Scenario.NORMAL, Duration.ofHours(12));

        assertThat(steps).allSatisfy(step -> assertThat(step.temperature()).isBetween(2.5, 7.5));
        assertThat(steps).allSatisfy(step -> assertThat(step.reading().orElseThrow().humidity()).isBetween(0.0, 100.0));
        assertThat(steps.stream().map(Step::temperature).distinct().count()).isGreaterThan(100);
    }

    @Test
    void sameSeedGivesTheSameReadings() {
        assertThat(run(Scenario.POWER_OUTAGE, Duration.ofMinutes(10)).stream().map(Step::reading).toList())
                .isEqualTo(run(Scenario.POWER_OUTAGE, Duration.ofMinutes(10)).stream().map(Step::reading).toList());
    }

    @Test
    void powerOutageWarmsPastTheMaximumThenCoolsBackDown() {
        List<Step> steps = run(Scenario.POWER_OUTAGE, Duration.ofMinutes(15));

        assertThat(during(steps, 0, 1)).allSatisfy(s -> assertThat(s.temperature()).isBetween(2.5, 7.5));
        // Out of range (> 8 °C) for several minutes during the outage (1 to 7 minutes)
        assertThat(during(steps, 5, 7)).allSatisfy(s -> assertThat(s.temperature()).isGreaterThan(8.0));
        assertThat(during(steps, 1, 7).stream().mapToDouble(Step::temperature).max().orElseThrow()).isGreaterThan(10.0);
        // Power is back: below 8 °C again within 2 minutes, and normal afterwards
        assertThat(during(steps, 9, 15)).allSatisfy(s -> assertThat(s.temperature()).isBetween(2.5, 7.5));
    }

    @Test
    void doorLeftOpenOnlyDuringTheIncident() {
        List<Step> steps = run(Scenario.DOOR_LEFT_OPEN, Duration.ofMinutes(6));

        assertThat(during(steps, 0, 1)).allSatisfy(s -> assertThat(s.reading().orElseThrow().doorOpen()).isFalse());
        assertThat(during(steps, 1, 4)).allSatisfy(s -> assertThat(s.reading().orElseThrow().doorOpen()).isTrue());
        assertThat(during(steps, 4, 6)).allSatisfy(s -> assertThat(s.reading().orElseThrow().doorOpen()).isFalse());
        assertThat(steps).allSatisfy(s -> assertThat(s.temperature()).isLessThanOrEqualTo(8.0));
    }

    @Test
    void deviceOfflinePublishesNothingForSevenMinutes() {
        List<Step> steps = run(Scenario.DEVICE_OFFLINE, Duration.ofMinutes(10));

        assertThat(during(steps, 0, 1)).allSatisfy(s -> assertThat(s.reading()).isPresent());
        assertThat(during(steps, 1, 8)).allSatisfy(s -> assertThat(s.reading()).isEmpty());
        assertThat(during(steps, 8, 10)).allSatisfy(s -> assertThat(s.reading()).isPresent());
    }

    @Test
    void lowBatteryDrainsBelowFifteenPercentThenIsReplaced() {
        List<Step> steps = run(Scenario.LOW_BATTERY, Duration.ofMinutes(8));

        assertThat(steps.getFirst().reading().orElseThrow().batteryLevel()).isEqualTo(20);
        assertThat(during(steps, 3, 6)).allSatisfy(s -> assertThat(s.reading().orElseThrow().batteryLevel()).isLessThan(15));
        assertThat(during(steps, 6, 8)).allSatisfy(s -> assertThat(s.reading().orElseThrow().batteryLevel()).isEqualTo(100));
    }

    @Test
    void invalidPayloadScenarioBreaksEveryThirdMessage() {
        SimulatedDevice device = device(Scenario.INVALID_PAYLOAD);
        List<Boolean> broken = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            device.nextReading(T0.plus(STEP.multipliedBy(i)));
            broken.add(device.sendsInvalidPayload());
        }

        assertThat(broken).containsExactly(false, false, true, false, false, true);
        assertThat(device(Scenario.NORMAL).sendsInvalidPayload()).isFalse();
    }

    private static SimulatedDevice device(Scenario scenario) {
        return new SimulatedDevice(new DeviceSpec("nile-pharmacy", "nile-fridge-01", scenario), new Random(42));
    }

    /** Simulates a device publishing every 5 seconds for the given duration. */
    private static List<Step> run(Scenario scenario, Duration duration) {
        SimulatedDevice device = device(scenario);
        List<Step> steps = new ArrayList<>();
        for (Duration elapsed = Duration.ZERO; elapsed.compareTo(duration) < 0; elapsed = elapsed.plus(STEP)) {
            steps.add(new Step(elapsed, device.nextReading(T0.plus(elapsed))));
        }
        return steps;
    }

    /** Steps with fromMinute <= elapsed < toMinute. */
    private static List<Step> during(List<Step> steps, int fromMinute, int toMinute) {
        return steps.stream()
                .filter(s -> s.elapsed().compareTo(Duration.ofMinutes(fromMinute)) >= 0
                        && s.elapsed().compareTo(Duration.ofMinutes(toMinute)) < 0)
                .toList();
    }
}
