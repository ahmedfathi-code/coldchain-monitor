package com.coldchain.api.alert;

/**
 * Lifecycle of an alert:
 * <pre>
 *   OPEN --acknowledge--> ACKNOWLEDGED --resolve--> RESOLVED
 *     \______________________resolve____________________/
 * </pre>
 * RESOLVED is also reached automatically when the device RECOVERED.
 */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
