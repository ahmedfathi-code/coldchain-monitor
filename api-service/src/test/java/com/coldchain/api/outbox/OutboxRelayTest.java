package com.coldchain.api.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private final OutboxRepository repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxRelay relay = new OutboxRelay(repository, kafka, Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofDays(7));

    private final OutboxEvent first = event("dev-1", "{\"v\":1}");
    private final OutboxEvent second = event("dev-1", "{\"v\":2}");
    private final OutboxEvent third = event("dev-2", null);

    @Test
    void publishesPendingEventsInOrderAndMarksThemPublished() {
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second, third));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(ok());

        relay.publishPending();

        var order = inOrder(kafka);
        order.verify(kafka).send("device.config", "dev-1", "{\"v\":1}");
        order.verify(kafka).send("device.config", "dev-1", "{\"v\":2}");
        order.verify(kafka).send("device.config", "dev-2", null); // tombstone
        assertThat(List.of(first, second, third)).allSatisfy(e -> assertThat(e.getPublishedAt()).isEqualTo(NOW));
    }

    @Test
    void stopsAtTheFirstFailureSoNothingOvertakesIt() {
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second, third));
        when(kafka.send("device.config", "dev-1", "{\"v\":1}")).thenReturn(ok());
        when(kafka.send("device.config", "dev-1", "{\"v\":2}"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.publishPending();

        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).contains("broker down");
        // The later event was not sent: it must not overtake the failed one.
        verify(kafka, never()).send("device.config", "dev-2", null);
        assertThat(third.getPublishedAt()).isNull();
    }

    @Test
    void theNextRunRetriesTheFailedEventFirst() {
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(second, third));
        when(kafka.send("device.config", "dev-1", "{\"v\":2}"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(ok());
        when(kafka.send("device.config", "dev-2", null)).thenReturn(ok());

        relay.publishPending(); // fails
        relay.publishPending(); // Kafka is back

        assertThat(second.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).isNull();
        assertThat(third.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void aRunStopsStartingNewSendsAfterTheMaxRunTime() {
        // A clock that moves 20 s forward with every send: a slow Kafka.
        Instant[] now = {NOW};
        Clock slowClock = new Clock() {
            public Instant instant() { return now[0]; }
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
        };
        OutboxRelay slowRelay = new OutboxRelay(repository, kafka, slowClock, Duration.ofDays(7));
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second, third));
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            now[0] = now[0].plusSeconds(20);
            return ok();
        });

        slowRelay.publishPending();

        // Sends start at 0 s and 20 s; at 40 s the 30 s budget is used up, so the run ends,
        // well before its 1-minute lock could expire. The third event waits for the next run.
        assertThat(OutboxRelay.MAX_RUN_TIME).isEqualTo(Duration.ofSeconds(30));
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
        assertThat(third.getPublishedAt()).isNull();
        verify(kafka, never()).send("device.config", "dev-2", null);
    }

    @Test
    void cleanupDeletesPublishedEventsOlderThanTheRetention() {
        relay.deleteOldPublishedEvents();

        verify(repository).deletePublishedBefore(NOW.minus(Duration.ofDays(7)));
    }

    private static OutboxEvent event(String key, String payload) {
        return new OutboxEvent("device.config", key, payload, NOW);
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(null);
    }
}
