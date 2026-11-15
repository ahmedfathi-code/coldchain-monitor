package com.coldchain.simulator;

import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.common.topic.MqttTopics;
import com.coldchain.simulator.SimulatorProperties.DeviceSpec;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** Every publish interval, asks each simulated device for a reading and publishes it. */
@Component
public class DeviceSimulation {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulation.class);

    private final List<SimulatedDevice> devices;
    private final MqttTelemetryPublisher publisher;
    private final Clock clock;

    public DeviceSimulation(SimulatorProperties properties, MqttTelemetryPublisher publisher, Clock clock) {
        Map<String, Scenario> overrides = ScenarioOverrides.parse(properties.scenarios());
        rejectUnknownDevices(overrides, properties.devices());

        Random random = new Random();
        this.devices = properties.devices().stream()
                .map(spec -> new DeviceSpec(spec.tenantId(), spec.deviceId(),
                        overrides.getOrDefault(spec.deviceId(), spec.scenario())))
                .map(spec -> new SimulatedDevice(spec, random))
                .toList();
        this.publisher = publisher;
        this.clock = clock;
        log.info("Simulating {} devices every {}", devices.size(), properties.publishInterval());
        devices.forEach(device -> log.info("  {}", device.spec()));
    }

    @Scheduled(fixedRateString = "${simulator.publish-interval}")
    public void publishReadings() {
        Instant now = clock.instant();
        for (SimulatedDevice device : devices) {
            Optional<TelemetryEvent> reading = device.nextReading(now);
            if (reading.isEmpty()) {
                log.debug("{} is offline, nothing published", device.spec().deviceId());
                continue;
            }
            publish(device, reading.get());
        }
    }

    private void publish(SimulatedDevice device, TelemetryEvent reading) {
        try {
            if (device.sendsInvalidPayload()) {
                publisher.publishRaw(MqttTopics.telemetryTopic(reading.tenantId(), reading.deviceId()),
                        InvalidPayloads.breakReading(reading));
                log.info("Published a deliberately invalid message for {}", reading.deviceId());
            } else {
                publisher.publish(reading);
                log.debug("Published {}", reading);
            }
        } catch (MqttException e) {
            // Like a real device: if the broker is unreachable, this reading is lost.
            log.warn("Could not publish reading {} of {}: {}", reading.sequence(), reading.deviceId(), e.getMessage());
        }
    }

    /** A typo in SIMULATOR_SCENARIOS should fail at startup, not be silently ignored. */
    private static void rejectUnknownDevices(Map<String, Scenario> overrides, List<DeviceSpec> devices) {
        Set<String> known = devices.stream().map(DeviceSpec::deviceId).collect(Collectors.toSet());
        overrides.keySet().stream()
                .filter(deviceId -> !known.contains(deviceId))
                .findFirst()
                .ifPresent(deviceId -> {
                    throw new IllegalArgumentException("Unknown device '" + deviceId
                            + "' in simulator scenarios, known devices: " + known);
                });
    }
}
