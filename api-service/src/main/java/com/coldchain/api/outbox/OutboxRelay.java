package com.coldchain.api.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes committed outbox rows to Kafka, oldest first.
 * <ul>
 *     <li>Only committed rows are visible to it, so a rolled-back change never reaches Kafka.</li>
 *     <li>Delivery is at least once: if the app crashes after Kafka accepted a message but
 *         before the row was marked published, the message is sent again. That is harmless
 *         for device.config, where the latest value per device wins.</li>
 *     <li>On the first failure the batch stops, so a later message for the same device can
 *         never overtake an earlier one; the next run retries from the same row.</li>
 * </ul>
 * With several api-service instances, ShedLock makes sure only ONE of them publishes at a
 * time (two relays could otherwise send messages of the same device out of order); see
 * {@link SchedulerLockConfig} and docs/decisions.md (ADR-033).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * A run stops starting new sends after this time. With one send taking at most ~20 s
     * (10 s max.block.ms + 10 s waiting for the acknowledgement), a run always ends well
     * before its lock expires (lockAtMostFor = 1 minute), so two runs can never overlap.
     */
    static final Duration MAX_RUN_TIME = Duration.ofSeconds(30);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final Duration retention;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate, Clock clock,
                       @Value("${outbox.retention:7d}") Duration retention) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.retention = retention;
    }

    /**
     * fixedDelay: on this instance, the next run starts only after the previous one finished.
     * {@code @SchedulerLock}: across instances, a run is skipped while another instance holds
     * the "outbox-relay" lock. The lock is released when the run ends; lockAtMostFor only
     * matters if the holder dies mid-run: then the lock expires by itself after 1 minute.
     */
    @Scheduled(fixedDelayString = "${outbox.poll-interval:1s}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M")
    public void publishPending() {
        Instant stopAt = clock.instant().plus(MAX_RUN_TIME);
        List<OutboxEvent> pending = repository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        for (OutboxEvent event : pending) {
            if (clock.instant().isAfter(stopAt)) {
                return; // the rest waits for the next run, which starts from the oldest pending row
            }
            try {
                // Wait for Kafka's acknowledgement (acks=all) before marking the row published.
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload()).get(10, TimeUnit.SECONDS);
                event.markPublished(clock.instant());
                repository.save(event);
                log.info("Published outbox event {} to {} (key {})", event.getId(), event.getTopic(), event.getMessageKey());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                event.markFailed(e.toString());
                repository.save(event);
                log.warn("Could not publish outbox event {} (attempt {}), will retry: {}",
                        event.getId(), event.getAttempts(), e.toString());
                return; // keep the order: do not publish later events before this one
            }
        }
    }

    /** Removes published rows older than the retention period, once an hour. */
    @Scheduled(fixedDelayString = "${outbox.cleanup-interval:1h}")
    public void deleteOldPublishedEvents() {
        int deleted = repository.deletePublishedBefore(clock.instant().minus(retention));
        if (deleted > 0) {
            log.info("Deleted {} published outbox events older than {}", deleted, retention);
        }
    }
}
