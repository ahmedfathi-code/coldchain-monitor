package com.coldchain.simulator;

/**
 * How a simulated device behaves. Every incident starts after one minute of normal behaviour
 * (so the dashboards show a normal baseline first) and happens once.
 * Exact timings are constants in {@link SimulatedDevice}.
 */
public enum Scenario {
    /** A healthy fridge: temperature around 5 °C with small noise, door closed, battery fine. */
    NORMAL,
    /** Power fails for 6 minutes: the temperature rises 1 °C per minute past 8 °C, then cools down. */
    POWER_OUTAGE,
    /** The door stays open for 3 minutes (the fridge warms up slightly), then closes. */
    DOOR_LEFT_OPEN,
    /** The device stops publishing for 7 minutes, then comes back. */
    DEVICE_OFFLINE,
    /** The battery starts at 20 % and drains 2 % per minute; it is replaced after 6 minutes. */
    LOW_BATTERY,
    /** Normal readings, but every third message is broken (to exercise the dead letter queue). */
    INVALID_PAYLOAD
}
