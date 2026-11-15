package com.coldchain.common.event;

/** Whether an alert condition has just started or has just ended. */
public enum AlertTransition {
    /** The condition started (emitted once, not repeated while it continues). */
    OPENED,
    /** The condition is over (emitted once when things are back to normal). */
    RECOVERED
}
