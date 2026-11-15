package com.coldchain.common.event;

/** How urgent an alert is. */
public enum Severity {
    /** Needs attention soon, medicines are not (yet) at risk. Example: low battery. */
    WARNING,
    /** Medicines may be damaged, act now. Example: temperature excursion. */
    CRITICAL
}
