package com.coldchain.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Settings under {@code simulator.*} in application.yml (can be overridden with env vars).
 *
 * @param publishInterval how often every device sends a reading
 * @param mqtt            broker connection settings
 * @param devices         the simulated devices; their ids must match the api-service seed data
 * @param scenarios       optional per-device scenario overrides, e.g. "nile-fridge-01=POWER_OUTAGE"
 */
@ConfigurationProperties("simulator")
public record SimulatorProperties(Duration publishInterval, Mqtt mqtt, List<DeviceSpec> devices, String scenarios) {

    public record Mqtt(String url, String username, String password, String clientId) {
    }

    public record DeviceSpec(String tenantId, String deviceId, Scenario scenario) {
    }
}
