package com.coldchain.ingestion;

import com.coldchain.common.topic.KafkaTopics;
import com.coldchain.ingestion.validation.TelemetryValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryIngestorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final String TOPIC = "coldchain/nile-pharmacy/devices/nile-fridge-01/telemetry";
    private static final String VALID_JSON = """
            {"deviceId":"nile-fridge-01","tenantId":"nile-pharmacy","timestamp":"2026-01-15T09:59:55Z",
             "sequence":12,"temperature":4.8,"humidity":51.2,"doorOpen":false,"batteryLevel":88}
            """;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final TelemetryIngestor ingestor =
            new TelemetryIngestor(new TelemetryValidator(CLOCK), kafkaTemplate, CLOCK, meters);

    @BeforeEach
    void kafkaAcceptsEverything() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, byte[]>) null));
    }

    @Test
    void validReadingGoesToTelemetryRawKeyedByDevice() {
        ingestor.ingest(TOPIC, bytes(VALID_JSON));

        ProducerRecord<String, byte[]> record = sentRecord();
        assertThat(record.topic()).isEqualTo(KafkaTopics.TELEMETRY_RAW);
        assertThat(record.key()).isEqualTo("nile-fridge-01");
        assertThat(new String(record.value(), StandardCharsets.UTF_8))
                .contains("\"deviceId\":\"nile-fridge-01\"")
                .contains("\"timestamp\":\"2026-01-15T09:59:55Z\"");
        assertThat(meters.counter("coldchain.telemetry.accepted").count()).isEqualTo(1);
    }

    @Test
    void invalidMessageGoesToTheDlqWithOriginalPayloadAndReasonHeaders() {
        byte[] payload = bytes("{\"deviceId\":\"nile-fridge-01\"}");

        ingestor.ingest(TOPIC, payload);

        ProducerRecord<String, byte[]> record = sentRecord();
        assertThat(record.topic()).isEqualTo(KafkaTopics.TELEMETRY_DLQ);
        assertThat(record.key()).isEqualTo("nile-fridge-01");
        assertThat(record.value()).isEqualTo(payload);
        assertThat(header(record, TelemetryIngestor.HEADER_ERROR_REASON)).startsWith("Missing required fields");
        assertThat(header(record, TelemetryIngestor.HEADER_MQTT_TOPIC)).isEqualTo(TOPIC);
        assertThat(header(record, TelemetryIngestor.HEADER_RECEIVED_AT)).isEqualTo("2026-01-15T10:00:00Z");
        assertThat(meters.counter("coldchain.telemetry.rejected", "reason", "missing_fields").count()).isEqualTo(1);
        assertThat(meters.counter("coldchain.telemetry.accepted").count()).isZero();
    }

    @Test
    void dlqRecordHasNoKeyWhenTheTopicCannotBeParsed() {
        ingestor.ingest("some/other/topic", bytes(VALID_JSON));

        ProducerRecord<String, byte[]> record = sentRecord();
        assertThat(record.topic()).isEqualTo(KafkaTopics.TELEMETRY_DLQ);
        assertThat(record.key()).isNull();
    }

    @Test
    void failsWhenKafkaCannotStoreTheRecordSoMqttDoesNotAcknowledgeIt() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(() -> ingestor.ingest(TOPIC, bytes(VALID_JSON)))
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("broker down");
        assertThat(meters.counter("coldchain.telemetry.accepted").count()).as("not stored, not counted").isZero();
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, byte[]> sentRecord() {
        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static String header(ProducerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
