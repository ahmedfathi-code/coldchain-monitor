package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.Severity;
import com.coldchain.common.event.TelemetryEvent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** TEMP_EXCURSION: temperature outside [minTemp, maxTemp] for at least excursionMinutes. */
public class TemperatureExcursionRule implements SustainedConditionRule {

    @Override
    public AlertType type() {
        return AlertType.TEMP_EXCURSION;
    }

    @Override
    public Severity severity() {
        return Severity.CRITICAL;
    }

    @Override
    public boolean isConditionMet(TelemetryEvent reading, DeviceConfigEvent config) {
        double temperature = reading.temperature();
        return temperature < config.minTemp() || temperature > config.maxTemp();
    }

    @Override
    public Duration minimumDuration(DeviceConfigEvent config) {
        return Duration.ofMinutes(config.excursionMinutes());
    }

    @Override
    public Map<String, Object> details(TelemetryEvent reading, DeviceConfigEvent config) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("temperature", reading.temperature());
        details.put("minTemp", config.minTemp());
        details.put("maxTemp", config.maxTemp());
        details.put("excursionMinutes", config.excursionMinutes());
        return details;
    }
}
