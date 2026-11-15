package com.coldchain.api.outbox;

import com.coldchain.api.device.DeviceDtos.CreateDeviceRequest;
import com.coldchain.api.device.DeviceRepository;
import com.coldchain.api.device.DeviceService;
import com.coldchain.api.device.DeviceType;
import com.coldchain.common.topic.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox against real MySQL and Kafka: device changes reach device.config
 * only when their database transaction commits.
 */
// ClickHouse is not needed here; point it at a closed port so no test can reach a real one.
@SpringBootTest(properties = "clickhouse.url=jdbc:clickhouse://localhost:1/none")
@Testcontainers
// Close the application after this class: its containers are stopped then, and a cached
// context would keep its scheduled outbox relay polling a database that no longer exists.
@DirtiesContext
class OutboxIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("coldchain");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    private static final String TENANT = "nile-pharmacy";

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DeviceRepository devices;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRepository outboxEvents;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void seededDevicesArePublishedOnStartup() {
        List<ConsumerRecord<String, String>> records = readDeviceConfig(Duration.ofSeconds(30), 4);

        assertThat(records).extracting(ConsumerRecord::key)
                .contains("nile-fridge-01", "nile-truck-01", "delta-fridge-01", "delta-fridge-02");
    }

    @Test
    void aCommittedDeviceChangeReachesKafkaAndIsMarkedPublished() {
        String id = newId();

        deviceService.create(TENANT, device(id, 9.0));

        ConsumerRecord<String, String> record = awaitMessage(id);
        assertThat(record.value())
                .contains("\"deviceId\":\"" + id + "\"")
                .contains("\"tenantId\":\"nile-pharmacy\"")
                .contains("\"maxTemp\":9.0")
                .contains("\"active\":true");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(eventsFor(id)).singleElement()
                        .satisfies(event -> assertThat(event.getPublishedAt()).isNotNull()));
    }

    @Test
    void aRolledBackChangeLeavesNoDeviceAndNoMessage() {
        String id = newId();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            deviceService.create(TENANT, device(id, 8.0));
            throw new IllegalStateException("something failed later in the same transaction");
        })).hasMessageContaining("something failed later");

        assertThat(devices.existsById(id)).isFalse();
        assertThat(eventsFor(id)).isEmpty();
        assertThat(findMessage(id, Duration.ofSeconds(3))).as("nothing published").isNull();
    }

    @Test
    void deletingADevicePublishesATombstone() {
        String id = newId();
        deviceService.create(TENANT, device(id, 8.0));

        deviceService.delete(TENANT, id);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<ConsumerRecord<String, String>> records = recordsFor(id);
            assertThat(records).hasSize(2);
            assertThat(records.get(1).value()).as("tombstone").isNull();
        });
    }

    @Test
    void whileAnotherInstanceHoldsTheRelayLockThisInstanceDoesNotPublish() throws InterruptedException {
        holdRelayLockAsAnotherInstance();
        String id = newId();
        try {
            deviceService.create(TENANT, device(id, 7.0));

            assertThat(findMessage(id, Duration.ofSeconds(5))).as("published while locked").isNull();
            assertThat(eventsFor(id)).singleElement()
                    .satisfies(event -> assertThat(event.getPublishedAt()).isNull());
        } finally {
            // The other instance finishes (or its lock expires): this instance takes over.
            jdbc.sql("UPDATE shedlock SET lock_until = UTC_TIMESTAMP(3) WHERE name = 'outbox-relay'").update();
        }

        awaitMessage(id);
        assertThat(jdbc.sql("SELECT locked_by FROM shedlock WHERE name = 'outbox-relay'").query(String.class).single())
                .isNotEqualTo("other-instance");
    }

    @Test
    void addingToTheOutboxOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.add(KafkaTopics.DEVICE_CONFIG, "x", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * Writes the lock row the way another api-service instance would. Done twice, 1 s apart:
     * a run of this instance that was already in progress releases the lock when it ends.
     */
    private void holdRelayLockAsAnotherInstance() throws InterruptedException {
        for (int i = 0; i < 2; i++) {
            jdbc.sql("""
                    INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
                    VALUES ('outbox-relay', UTC_TIMESTAMP(3) + INTERVAL 1 HOUR, UTC_TIMESTAMP(3), 'other-instance')
                    ON DUPLICATE KEY UPDATE lock_until = VALUES(lock_until), locked_at = VALUES(locked_at),
                                            locked_by = VALUES(locked_by)
                    """).update();
            Thread.sleep(1000);
        }
    }

    private static String newId() {
        return "outbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static CreateDeviceRequest device(String id, double maxTemp) {
        return new CreateDeviceRequest(id, "SN-" + id, "Test fridge", DeviceType.FRIDGE, "Lab",
                2.0, maxTemp, 15, 5, true);
    }

    private List<OutboxEvent> eventsFor(String key) {
        return outboxEvents.findAll().stream().filter(e -> e.getMessageKey().equals(key)).toList();
    }

    private ConsumerRecord<String, String> awaitMessage(String key) {
        ConsumerRecord<String, String> record = findMessage(key, Duration.ofSeconds(30));
        assertThat(record).as("message for " + key + " on device.config").isNotNull();
        return record;
    }

    private ConsumerRecord<String, String> findMessage(String key, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<ConsumerRecord<String, String>> records = recordsFor(key);
            if (!records.isEmpty()) {
                return records.getFirst();
            }
        }
        return null;
    }

    private List<ConsumerRecord<String, String>> recordsFor(String key) {
        return readDeviceConfig(Duration.ofSeconds(2), Integer.MAX_VALUE).stream()
                .filter(record -> key.equals(record.key()))
                .toList();
    }

    /** Reads device.config from the beginning for a while (or until enough records were read). */
    private List<ConsumerRecord<String, String>> readDeviceConfig(Duration duration, int enough) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaTopics.DEVICE_CONFIG));
            Instant deadline = Instant.now().plus(duration);
            while (Instant.now().isBefore(deadline) && records.size() < enough) {
                consumer.poll(Duration.ofMillis(300)).forEach(records::add);
            }
        }
        return records;
    }
}
