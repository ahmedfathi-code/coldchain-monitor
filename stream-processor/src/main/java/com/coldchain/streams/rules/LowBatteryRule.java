package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.Severity;
import com.coldchain.common.event.TelemetryEvent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** LOW_BATTERY: battery below the threshold (15 % by default). Alerts immediately. */
public class LowBatteryRule implements SustainedConditionRule {

    private final int threshold;

    public LowBatteryRule(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public AlertType type() {
        return AlertType.LOW_BATTERY;
    }

    @Override
    public Severity severity() {
        return Severity.WARNING;
    }

    @Override
    public boolean isConditionMet(TelemetryEvent reading, DeviceConfigEvent config) {
        return reading.batteryLevel() < threshold;
    }

    /** Zero: the first low reading opens the alert, no need to wait. */
    @Override
    public Duration minimumDuration(DeviceConfigEvent config) {
        return Duration.ZERO;
    }

    @Override
    public Map<String, Object> details(TelemetryEvent reading, DeviceConfigEvent config) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("batteryLevel", reading.batteryLevel());
        details.put("threshold", threshold);
        return details;
    }
}
