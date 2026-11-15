package com.coldchain.common.event;

/** The dangerous conditions the stream processor can detect. */
public enum AlertType {
    /** Temperature outside [minTemp, maxTemp] for at least excursionMinutes. */
    TEMP_EXCURSION,
    /** Door open for longer than doorOpenMaxMinutes. */
    DOOR_OPEN_TOO_LONG,
    /** No reading received from the device for a while. */
    DEVICE_OFFLINE,
    /** Battery level below the low-battery threshold. */
    LOW_BATTERY
}
