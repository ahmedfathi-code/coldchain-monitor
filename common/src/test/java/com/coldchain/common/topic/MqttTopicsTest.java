package com.coldchain.common.topic;

import com.coldchain.common.topic.MqttTopics.DeviceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MqttTopicsTest {

    @Test
    void buildsTheTelemetryTopicOfADevice() {
        assertThat(MqttTopics.telemetryTopic("pharmacy-a", "fridge-001"))
                .isEqualTo("coldchain/pharmacy-a/devices/fridge-001/telemetry");
    }

    @Test
    void filterHasOneWildcardForTenantAndOneForDevice() {
        assertThat(MqttTopics.TELEMETRY_FILTER).isEqualTo("coldchain/+/devices/+/telemetry");
    }

    @Test
    void parsesTenantAndDeviceFromATelemetryTopic() {
        assertThat(MqttTopics.parseTelemetryTopic("coldchain/pharmacy-a/devices/fridge-001/telemetry"))
                .contains(new DeviceRef("pharmacy-a", "fridge-001"));
    }

    @Test
    void parseIsTheInverseOfBuild() {
        String topic = MqttTopics.telemetryTopic("t1", "d1");

        assertThat(MqttTopics.parseTelemetryTopic(topic)).contains(new DeviceRef("t1", "d1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "coldchain/pharmacy-a/devices/fridge-001",
            "coldchain/pharmacy-a/devices/fridge-001/telemetry/extra",
            "other/pharmacy-a/devices/fridge-001/telemetry",
            "coldchain/pharmacy-a/sensors/fridge-001/telemetry",
            "coldchain//devices/fridge-001/telemetry",
            "coldchain/pharmacy-a/devices//telemetry",
            "coldchain/pharmacy-a/devices/fridge-001/status"
    })
    void rejectsTopicsWithTheWrongShape(String topic) {
        assertThat(MqttTopics.parseTelemetryTopic(topic)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(MqttTopics.parseTelemetryTopic(null)).isEmpty();
    }
}
