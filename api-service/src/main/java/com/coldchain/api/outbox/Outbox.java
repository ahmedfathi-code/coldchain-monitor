package com.coldchain.api.outbox;

import com.coldchain.common.json.EventJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Transactional outbox: "send this message to Kafka once the current transaction commits".
 * <p>
 * The problem it solves ("dual write"): a device change goes to MySQL AND its thresholds must
 * go to Kafka. Writing to both directly can never be atomic: one can succeed and the other
 * fail. Instead, the message is stored as a row in the same MySQL transaction as the change
 * itself: both are saved, or neither. The {@link OutboxRelay} then publishes committed rows.
 */
@Service
public class Outbox {

    private final OutboxRepository repository;
    private final Clock clock;

    public Outbox(OutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Queues a message for Kafka. MANDATORY: calling this outside a transaction is a bug (the
     * message would no longer be tied to the business change), so Spring throws immediately.
     *
     * @param value the message value, serialized to JSON; null publishes a tombstone
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, String key, Object value) {
        String payload = value == null ? null : EventJson.mapper().writeValueAsString(value);
        repository.save(new OutboxEvent(topic, key, payload, clock.instant()));
    }
}
