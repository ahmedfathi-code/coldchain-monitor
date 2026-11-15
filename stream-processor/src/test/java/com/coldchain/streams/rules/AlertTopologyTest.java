package com.coldchain.streams.rules;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertTransition;
import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.DeviceConfigEvent;
import com.coldchain.common.event.Severity;
import com.coldchain.common.event.TelemetryEvent;
import com.coldchain.common.json.JsonDeserializer;
import com.coldchain.common.json.JsonSerializer;
import com.coldchain.common.topic.KafkaTopics;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real alert topology in memory with TopologyTestDriver: no Kafka broker needed.
 * We pipe readings into telemetry.raw and check what comes out of the alerts topic.
 */
class AlertTopologyTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final String DEVICE = "nile-fridge-01";
    private static final String TENANT = "nile-pharmacy";

    private static final RulesProperties RULES = new RulesProperties(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(30), 15);

    private TopologyTestDriver driver;
    private TestInputTopic<String, TelemetryEvent> telemetry;
    private TestInputTopic<String, DeviceConfigEvent> deviceConfig;
    private TestOutputTopic<String, AlertEvent> alerts;
    private long sequence = 0;

    @BeforeEach
    void startTopology() {
        StreamsBuilder builder = new StreamsBuilder();
        AlertTopology.build(builder, RULES);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "alert-topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        // T0 is also the initial wall-clock time of the driver (used by the offline punctuator).
        driver = new TopologyTestDriver(builder.build(), props, T0);

        telemetry = driver.createInputTopic(KafkaTopics.TELEMETRY_RAW, new StringSerializer(), new JsonSerializer<>());
        deviceConfig = driver.createInputTopic(KafkaTopics.DEVICE_CONFIG, new StringSerializer(), new JsonSerializer<>());
        alerts = driver.createOutputTopic(KafkaTopics.ALERTS, new StringDeserializer(), new JsonDeserializer<>(AlertEvent.class));
    }

    @AfterEach
    void closeTopology() {
        driver.close();
    }

    @Nested
    class TemperatureExcursion {

        @Test
        void noAlertWhileTheExcursionIsShorterThanExcursionMinutes() {
            for (int minute = 0; minute <= 14; minute++) {
                send(minute, 9.0);
            }

            assertThat(alertsOf(AlertType.TEMP_EXCURSION)).isEmpty();
        }

        @Test
        void opensOnceWhenOutOfRangeForExcursionMinutesAndDoesNotRepeatWhileItContinues() {
            for (int minute = 0; minute <= 25; minute++) {
                send(minute, 9.0);
            }

            List<AlertEvent> raised = alertsOf(AlertType.TEMP_EXCURSION);
            assertThat(raised).hasSize(1);
            AlertEvent opened = raised.getFirst();
            assertThat(opened.transition()).isEqualTo(AlertTransition.OPENED);
            assertThat(opened.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(opened.deviceId()).isEqualTo(DEVICE);
            assertThat(opened.tenantId()).isEqualTo(TENANT);
            assertThat(opened.alertId()).isNotBlank();
            assertThat(opened.detectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
            assertThat(opened.details())
                    .containsEntry("temperature", 9.0)
                    .containsEntry("maxTemp", 8.0)
                    .containsEntry("since", T0.toString())
                    .containsEntry("durationMinutes", 15);
        }

        @Test
        void recoversOnceWhenTheTemperatureIsBackInRange() {
            for (int minute = 0; minute <= 20; minute++) {
                send(minute, 9.0);
            }
            send(21, 7.5);
            send(22, 5.0);

            List<AlertEvent> raised = alertsOf(AlertType.TEMP_EXCURSION);
            assertThat(raised).extracting(AlertEvent::transition)
                    .containsExactly(AlertTransition.OPENED, AlertTransition.RECOVERED);
            AlertEvent recovered = raised.get(1);
            assertThat(recovered.detectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(21)));
            assertThat(recovered.details()).containsEntry("durationMinutes", 21);
        }

        @Test
        void tooLowCountsAsOutOfRangeToo() {
            for (int minute = 0; minute <= 15; minute++) {
                send(minute, 1.0);
            }

            assertThat(alertsOf(AlertType.TEMP_EXCURSION)).hasSize(1);
        }

        @Test
        void oneReadingBackInRangeRestartsTheCountdown() {
            for (int minute = 0; minute <= 10; minute++) {
                send(minute, 9.0);
            }
            send(11, 6.0);
            for (int minute = 12; minute <= 25; minute++) {
                send(minute, 9.0);
            }

            assertThat(alertsOf(AlertType.TEMP_EXCURSION)).isEmpty();
        }

        @Test
        void noRecoveredWithoutOpened() {
            send(0, 9.0);
            send(1, 5.0);

            assertThat(alertsOf(AlertType.TEMP_EXCURSION)).isEmpty();
        }

        @Test
        void usesTheDeviceThresholdsFromDeviceConfig() {
            deviceConfig.pipeInput(DEVICE, config(true, 10.0, 2));

            send(0, 9.0);   // in range for this device (max 10)
            send(1, 11.0);
            send(2, 11.0);
            send(3, 11.0);  // 2 minutes out of range -> OPENED

            List<AlertEvent> raised = alertsOf(AlertType.TEMP_EXCURSION);
            assertThat(raised).hasSize(1);
            assertThat(raised.getFirst().detectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(3)));
            assertThat(raised.getFirst().details()).containsEntry("maxTemp", 10.0).containsEntry("excursionMinutes", 2);
        }
    }

    @Nested
    class DoorOpenTooLong {

        @Test
        void opensAfterDoorOpenMaxMinutesAndRecoversWhenClosed() {
            for (int minute = 0; minute <= 8; minute++) {
                send(minute, 5.0, true, 90);
            }
            send(9, 5.0, false, 90);

            List<AlertEvent> raised = alertsOf(AlertType.DOOR_OPEN_TOO_LONG);
            assertThat(raised).extracting(AlertEvent::transition)
                    .containsExactly(AlertTransition.OPENED, AlertTransition.RECOVERED);
            assertThat(raised.get(0).detectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
            assertThat(raised.get(0).severity()).isEqualTo(Severity.WARNING);
            assertThat(raised.get(1).details()).containsEntry("durationMinutes", 9);
        }

        @Test
        void briefDoorOpeningsDoNotAlert() {
            send(0, 5.0, true, 90);
            send(3, 5.0, false, 90);
            send(4, 5.0, true, 90);
            send(8, 5.0, true, 90);
            send(9, 5.0, false, 90);

            assertThat(alertsOf(AlertType.DOOR_OPEN_TOO_LONG)).isEmpty();
        }
    }

    @Nested
    class LowBattery {

        @Test
        void opensImmediatelyBelowTheThresholdOnceAndRecoversWhenReplaced() {
            send(0, 5.0, false, 16);
            send(1, 5.0, false, 15);  // not below 15
            send(2, 5.0, false, 14);  // below -> OPENED
            send(3, 5.0, false, 12);  // still low -> nothing new
            send(4, 5.0, false, 100); // battery replaced -> RECOVERED

            List<AlertEvent> raised = alertsOf(AlertType.LOW_BATTERY);
            assertThat(raised).extracting(AlertEvent::transition)
                    .containsExactly(AlertTransition.OPENED, AlertTransition.RECOVERED);
            assertThat(raised.get(0).detectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
            assertThat(raised.get(0).details()).containsEntry("batteryLevel", 14).containsEntry("threshold", 15);
        }
    }

    @Nested
    class DeviceOffline {

        @Test
        void opensOnceWhenNothingArrivesForFiveMinutesAndRecoversOnTheNextReading() {
            send(0, 5.0);

            driver.advanceWallClockTime(Duration.ofMinutes(4));
            assertThat(alertsOf(AlertType.DEVICE_OFFLINE)).as("not offline after 4 minutes").isEmpty();

            driver.advanceWallClockTime(Duration.ofMinutes(1));
            driver.advanceWallClockTime(Duration.ofMinutes(3)); // punctuator runs again: no duplicate
            List<AlertEvent> opened = alertsOf(AlertType.DEVICE_OFFLINE);
            assertThat(opened).hasSize(1);
            assertThat(opened.getFirst().transition()).isEqualTo(AlertTransition.OPENED);
            assertThat(opened.getFirst().tenantId()).isEqualTo(TENANT);
            assertThat(opened.getFirst().details()).containsEntry("lastSeenAt", T0.toString());

            send(8, 5.0);
            List<AlertEvent> recovered = alertsOf(AlertType.DEVICE_OFFLINE);
            assertThat(recovered).extracting(AlertEvent::transition).containsExactly(AlertTransition.RECOVERED);
            assertThat(recovered.getFirst().details()).containsEntry("offlineMinutes", 8);
        }

        @Test
        void aDeviceThatKeepsReportingIsNeverOffline() {
            for (int minute = 0; minute <= 20; minute++) {
                send(minute, 5.0);
                driver.advanceWallClockTime(Duration.ofMinutes(1));
            }

            assertThat(alertsOf(AlertType.DEVICE_OFFLINE)).isEmpty();
        }
    }

    @Nested
    class InactiveDevices {

        @Test
        void anInactiveDeviceRaisesNoAlertsWhateverItReports() {
            deviceConfig.pipeInput(DEVICE, config(false, 8.0, 15));

            for (int minute = 0; minute <= 30; minute++) {
                send(minute, 12.0, true, 5); // too warm, door open, battery empty
            }

            assertThat(alerts.readValuesToList()).isEmpty();
        }

        @Test
        void anInactiveDeviceThatGoesSilentIsNotReportedOffline() {
            deviceConfig.pipeInput(DEVICE, config(false, 8.0, 15));
            send(0, 5.0);

            driver.advanceWallClockTime(Duration.ofMinutes(10));

            assertThat(alertsOf(AlertType.DEVICE_OFFLINE)).isEmpty();
        }

        @Test
        void aSilentDeviceIsReportedOfflineOnceItIsActiveAgain() {
            deviceConfig.pipeInput(DEVICE, config(false, 8.0, 15));
            send(0, 5.0);
            driver.advanceWallClockTime(Duration.ofMinutes(10));

            deviceConfig.pipeInput(DEVICE, config(true, 8.0, 15));
            driver.advanceWallClockTime(Duration.ofMinutes(1)); // next punctuation

            assertThat(alertsOf(AlertType.DEVICE_OFFLINE)).extracting(AlertEvent::transition)
                    .containsExactly(AlertTransition.OPENED);
        }

        @Test
        void afterReactivationAConditionIsCountedFromScratch() {
            for (int minute = 0; minute <= 10; minute++) {
                send(minute, 9.0); // out of range for 10 minutes: not yet an alert
            }
            deviceConfig.pipeInput(DEVICE, config(false, 8.0, 15));
            send(11, 9.0); // inactive: the pending excursion is forgotten
            deviceConfig.pipeInput(DEVICE, config(true, 8.0, 15));

            for (int minute = 12; minute <= 26; minute++) {
                send(minute, 9.0);
            }
            assertThat(alertsOf(AlertType.TEMP_EXCURSION)).as("only 14 minutes since re-activation").isEmpty();

            send(27, 9.0);
            assertThat(alertsOf(AlertType.TEMP_EXCURSION)).singleElement()
                    .satisfies(alert -> assertThat(alert.details()).containsEntry("since", T0.plus(Duration.ofMinutes(12)).toString()));
        }
    }

    @Nested
    class LateData {

        @Test
        void readingOlderThanTheGracePeriodIsIgnored() {
            for (int minute = 0; minute <= 10; minute++) {
                send(minute, 9.0);
            }
            send(5, 5.0);   // 5 minutes late: would reset the excursion, but is dropped
            for (int minute = 11; minute <= 15; minute++) {
                send(minute, 9.0);
            }

            assertThat(alertsOf(AlertType.TEMP_EXCURSION)).hasSize(1);
        }

        @Test
        void slightlyLateReadingWithinTheGracePeriodIsStillUsed() {
            send(10, 5.0, false, 90);
            sendAt(T0.plus(Duration.ofSeconds(9 * 60 + 30)), 5.0, false, 10); // 30 s late

            assertThat(alertsOf(AlertType.LOW_BATTERY)).hasSize(1);
        }
    }

    private void send(int minute, double temperature) {
        send(minute, temperature, false, 90);
    }

    private void send(int minute, double temperature, boolean doorOpen, int battery) {
        sendAt(T0.plus(Duration.ofMinutes(minute)), temperature, doorOpen, battery);
    }

    private void sendAt(Instant eventTime, double temperature, boolean doorOpen, int battery) {
        sequence++;
        telemetry.pipeInput(DEVICE, new TelemetryEvent(DEVICE, TENANT, eventTime, sequence,
                temperature, 50.0, doorOpen, battery));
    }

    private static DeviceConfigEvent config(boolean active, double maxTemp, int excursionMinutes) {
        return new DeviceConfigEvent(DEVICE, TENANT, 2.0, maxTemp, excursionMinutes, 5, active);
    }

    /** Reads everything emitted so far and keeps the alerts of one type. */
    private List<AlertEvent> alertsOf(AlertType type) {
        return alerts.readValuesToList().stream().filter(alert -> alert.type() == type).toList();
    }
}
