package com.coldchain.api.telemetry;

import com.coldchain.api.telemetry.TelemetryDtos.HourlyStat;
import com.coldchain.api.telemetry.TelemetryDtos.TelemetryPoint;
import com.coldchain.api.telemetry.TelemetryRepository.ComplianceStats;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Runs the ClickHouse queries against a real ClickHouse (Docker, via Testcontainers) that is
 * initialised with the project's real schema, infra/clickhouse/init.sql.
 */
@Testcontainers
class TelemetryRepositoryIT {

    @Container
    static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
            .withEnv("CLICKHOUSE_USER", "test")
            .withEnv("CLICKHOUSE_PASSWORD", "test")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123));

    private static final String TENANT = "nile-pharmacy";
    private static final String DEVICE = "nile-fridge-01";
    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static TelemetryRepository repository;

    @BeforeAll
    static void createSchemaAndData() throws IOException {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:clickhouse://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        dataSource.setUsername("test");
        dataSource.setPassword("test");
        jdbc = JdbcClient.create(dataSource);
        runInitSql();
        repository = new TelemetryRepository(jdbc);

        // One reading per minute for 10 minutes; range is 2-8 °C.
        // Out of range: minutes 2, 3, 4 (one excursion of 3 min) and minute 7 (one of 1 min).
        double[] temperatures = {5, 5, 9, 9, 9, 5, 5, 1, 5, 5};
        for (int minute = 0; minute < temperatures.length; minute++) {
            insert(TENANT, DEVICE, T0.plus(Duration.ofMinutes(minute)), minute + 1, temperatures[minute]);
        }
        // The device was then offline for 20 minutes: that gap is not "monitored" time.
        insert(TENANT, DEVICE, T0.plus(Duration.ofMinutes(30)), 11, 5.0);
        // Next hour
        insert(TENANT, DEVICE, T0.plus(Duration.ofMinutes(65)), 12, 4.0);
        // Other device and other tenant: must never be counted.
        insert(TENANT, "nile-truck-01", T0, 1, 20.0);
        insert("delta-pharmacy", DEVICE, T0.plus(Duration.ofMinutes(1)), 1, 30.0);
    }

    @AfterAll
    static void close() {
        dataSource.close();
    }

    @Test
    void readingsArePagedAndScopedToTenantAndDevice() {
        Instant to = T0.plus(Duration.ofHours(2));

        List<TelemetryPoint> firstPage = repository.findReadings(TENANT, DEVICE, T0, to, false, 5, 0);
        List<TelemetryPoint> lastPage = repository.findReadings(TENANT, DEVICE, T0, to, false, 5, 10);

        assertThat(repository.countReadings(TENANT, DEVICE, T0, to)).isEqualTo(12);
        assertThat(firstPage).extracting(TelemetryPoint::sequence).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(firstPage.getFirst().timestamp()).isEqualTo(T0);
        assertThat(lastPage).extracting(TelemetryPoint::sequence).containsExactly(11L, 12L);
        assertThat(firstPage).allSatisfy(point -> assertThat(point.temperature()).isLessThan(20.0));
    }

    @Test
    void newestFirstReversesTheOrder() {
        List<TelemetryPoint> page = repository.findReadings(TENANT, DEVICE, T0, T0.plus(Duration.ofHours(2)), true, 2, 0);

        assertThat(page).extracting(TelemetryPoint::sequence).containsExactly(12L, 11L);
    }

    @Test
    void periodIsFromInclusiveToExclusive() {
        Instant from = T0.plus(Duration.ofMinutes(2));
        Instant to = T0.plus(Duration.ofMinutes(4));

        assertThat(repository.countReadings(TENANT, DEVICE, from, to)).isEqualTo(2);
    }

    @Test
    void hourlyStatsComeFromTheAggregatedTable() {
        List<HourlyStat> hours = repository.findHourly(TENANT, DEVICE, T0, T0.plus(Duration.ofHours(3)), false, 24, 0);

        assertThat(repository.countHours(TENANT, DEVICE, T0, T0.plus(Duration.ofHours(3)))).isEqualTo(2);
        assertThat(hours).hasSize(2);
        HourlyStat first = hours.getFirst();
        assertThat(first.hour()).isEqualTo(T0);
        assertThat(first.readings()).isEqualTo(11);
        assertThat(first.minTemperature()).isEqualTo(1.0);
        assertThat(first.maxTemperature()).isEqualTo(9.0);
        assertThat(first.avgTemperature()).isCloseTo(63.0 / 11, within(0.01));
        assertThat(hours.get(1).hour()).isEqualTo(T0.plus(Duration.ofHours(1)));
    }

    @Test
    void complianceCountsOutOfRangeTimeAndExcursions() {
        ComplianceStats stats = repository.compliance(TENANT, DEVICE, T0, T0.plus(Duration.ofMinutes(31)), 2.0, 8.0);

        assertThat(stats.readings()).isEqualTo(11);
        // 9 one-minute gaps between the first 10 readings; the 20-minute gap is not monitored.
        assertThat(stats.monitoredMillis()).isEqualTo(Duration.ofMinutes(9).toMillis());
        // Readings at minutes 2, 3, 4 and 7 each cover one minute out of range.
        assertThat(stats.outOfRangeMillis()).isEqualTo(Duration.ofMinutes(4).toMillis());
        assertThat(stats.excursions()).isEqualTo(2);
        assertThat(stats.minTemperature()).isEqualTo(1.0);
        assertThat(stats.maxTemperature()).isEqualTo(9.0);
        assertThat(stats.avgTemperature()).isCloseTo(63.0 / 11, within(0.001));
    }

    @Test
    void complianceWithoutDataReturnsZeroReadings() {
        ComplianceStats stats = repository.compliance(TENANT, "unknown-device", T0, T0.plus(Duration.ofHours(1)), 2.0, 8.0);

        assertThat(stats.readings()).isZero();
        assertThat(stats.monitoredMillis()).isZero();
    }

    private static void insert(String tenantId, String deviceId, Instant ts, long sequence, double temperature) {
        jdbc.sql("""
                        INSERT INTO coldchain.telemetry
                        (tenant_id, device_id, ts, sequence, temperature, humidity, door_open, battery_level)
                        VALUES (?, ?, fromUnixTimestamp64Milli(?, 'UTC'), ?, ?, 50.0, false, 90)
                        """)
                .params(tenantId, deviceId, ts.toEpochMilli(), sequence, temperature)
                .update();
    }

    /** Executes the real infra/clickhouse/init.sql, one statement at a time. */
    private static void runInitSql() throws IOException {
        String script = Files.readAllLines(Path.of("../infra/clickhouse/init.sql")).stream()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        Arrays.stream(script.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .forEach(statement -> jdbc.sql(statement).update());
    }
}
