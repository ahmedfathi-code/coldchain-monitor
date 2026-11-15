package com.coldchain.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** Fake devices that publish telemetry to MQTT, with failure scenarios. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DeviceSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeviceSimulatorApplication.class, args);
    }

    /** Injected instead of calling Instant.now() directly, so tests can use a fixed clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
