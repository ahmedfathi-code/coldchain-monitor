package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.Severity;
import com.coldchain.common.event.TelemetryEvent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** DOOR_OPEN_TOO_LONG: door open continuously for doorOpenMaxMinutes or more. */
public class DoorOpenTooLongRule implements SustainedConditionRule {

    @Override
    public AlertType type() {
        return AlertType.DOOR_OPEN_TOO_LONG;
    }

    @Override
    public Severity severity() {
        // The medicines are not damaged yet; TEMP_EXCURSION fires if the fridge warms up.
        return Severity.WARNING;
    }

    @Override
    public boolean isConditionMet(TelemetryEvent reading, DeviceConfigEvent config) {
        return reading.doorOpen();
    }

    @Override
    public Duration minimumDuration(DeviceConfigEvent config) {
        return Duration.ofMinutes(config.doorOpenMaxMinutes());
    }

    @Override
    public Map<String, Object> details(TelemetryEvent reading, DeviceConfigEvent config) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("doorOpen", reading.doorOpen());
        details.put("temperature", reading.temperature());
        details.put("doorOpenMaxMinutes", config.doorOpenMaxMinutes());
        return details;
    }
}
