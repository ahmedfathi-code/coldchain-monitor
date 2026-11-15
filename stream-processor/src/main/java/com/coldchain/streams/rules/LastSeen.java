package com.coldchain.streams.rules;

/**
 * What the {@link DeviceOfflineProcessor} remembers about one device (state store value).
 *
 * @param tenantId       tenant of the device (needed to build the alert)
 * @param lastSeenMillis wall-clock time we last received a reading from the device
 * @param offline        true once DEVICE_OFFLINE was OPENED, so it is emitted only once
 */
public record LastSeen(String tenantId, long lastSeenMillis, boolean offline) {
}
