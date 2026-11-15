package com.coldchain.common.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceConfigEventTest {

    @Test
    void defaultsMatchTheDocumentedValues() {
        DeviceConfigEvent defaults = DeviceConfigEvent.defaultsFor("fridge-001", "pharmacy-a");

        assertThat(defaults.deviceId()).isEqualTo("fridge-001");
        assertThat(defaults.tenantId()).isEqualTo("pharmacy-a");
        assertThat(defaults.minTemp()).isEqualTo(2.0);
        assertThat(defaults.maxTemp()).isEqualTo(8.0);
        assertThat(defaults.excursionMinutes()).isEqualTo(15);
        assertThat(defaults.doorOpenMaxMinutes()).isEqualTo(5);
        assertThat(defaults.active()).isTrue();
    }
}
