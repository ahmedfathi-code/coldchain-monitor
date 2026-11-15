package com.coldchain.streams.rules;

import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.TelemetryEvent;

/** A reading together with the thresholds of its device (result of the GlobalKTable join). */
public record ReadingWithConfig(TelemetryEvent reading, DeviceConfigEvent config) {
}
