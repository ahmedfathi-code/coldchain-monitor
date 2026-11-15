package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.Severity;
import com.coldchain.common.event.TelemetryEvent;

import java.time.Duration;
import java.util.Map;

/**
 * A rule of the form "alert when a condition holds continuously for at least N minutes".
 * TEMP_EXCURSION, DOOR_OPEN_TOO_LONG and LOW_BATTERY (N = 0) all follow this pattern, so
 * they share one state machine: {@link SustainedConditionProcessor}.
 */
public interface SustainedConditionRule {

    AlertType type();

    Severity severity();

    /** True while the dangerous condition holds for this reading. */
    boolean isConditionMet(TelemetryEvent reading, DeviceConfigEvent config);

    /** How long the condition must hold without interruption before the alert is OPENED. */
    Duration minimumDuration(DeviceConfigEvent config);

    /** Rule-specific facts for the alert (current value, threshold, ...). */
    Map<String, Object> details(TelemetryEvent reading, DeviceConfigEvent config);
}
