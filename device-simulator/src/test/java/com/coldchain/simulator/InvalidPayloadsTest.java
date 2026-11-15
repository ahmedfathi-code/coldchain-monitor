package com.coldchain.simulator;

import com.coldchain.common.event.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InvalidPayloadsTest {

    @Test
    void rotatesThroughFourDifferentKindsOfBrokenPayload() {
        // Invalid payloads are sent for sequences 3, 6, 9, 12, ...
        assertThat(text(3)).doesNotStartWith("{");                        // not JSON
        assertThat(text(6)).contains("\"temperature\":null");             // missing field
        assertThat(text(9)).contains("\"temperature\":999.0");            // impossible value
        assertThat(text(12)).contains("\"tenantId\":\"someone-else\"");   // wrong tenant
        assertThat(text(15)).doesNotStartWith("{");                       // and again
    }

    private static String text(long sequence) {
        TelemetryEvent reading = new TelemetryEvent("nile-fridge-01", "nile-pharmacy",
                Instant.parse("2026-01-15T10:00:00Z"), sequence, 5.0, 50.0, false, 90);
        return new String(InvalidPayloads.breakReading(reading), StandardCharsets.UTF_8);
    }
}
