package com.coldchain.api.telemetry;

import com.coldchain.api.telemetry.TelemetryDtos.HourlyStat;
import com.coldchain.api.telemetry.TelemetryDtos.TelemetryPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * SQL queries against ClickHouse (tables created by infra/clickhouse/init.sql).
 * <p>
 * Every query filters on tenant_id AND device_id: the (tenant_id, device_id, ts) sort key of
 * the tables makes this fast, and it keeps tenant isolation even at this level.
 * Values are always passed as {@code ?} parameters (never concatenated) to prevent SQL
 * injection. Times travel as epoch milliseconds / seconds to avoid any time-zone confusion.
 */
@Repository
public class TelemetryRepository {

    /** Gaps longer than this mean the device was offline: that time counts as not monitored. */
    static final Duration MAX_GAP = Duration.ofMinutes(5);

    private static final String DEVICE_AND_PERIOD = """
            tenant_id = ? AND device_id = ?
              AND ts >= fromUnixTimestamp64Milli(?, 'UTC') AND ts < fromUnixTimestamp64Milli(?, 'UTC')
            """;

    /** Hours that overlap [from, to): the hour containing "from" is included. */
    private static final String HOURLY_DEVICE_AND_PERIOD = """
            tenant_id = ? AND device_id = ?
              AND hour >= toStartOfHour(fromUnixTimestamp64Milli(?, 'UTC')) AND hour < fromUnixTimestamp64Milli(?, 'UTC')
            """;

    private final JdbcClient clickHouse;

    public TelemetryRepository(@Qualifier(ClickHouseConfig.CLICKHOUSE) JdbcClient clickHouse) {
        this.clickHouse = clickHouse;
    }

    /** One page of raw readings, oldest first (or newest first when {@code newestFirst}). */
    public List<TelemetryPoint> findReadings(String tenantId, String deviceId, Instant from, Instant to,
                                             boolean newestFirst, int limit, long offset) {
        // ASC/DESC cannot be a "?" parameter; it comes from a boolean, never from user text.
        String sql = """
                SELECT toUnixTimestamp64Milli(ts) AS ts_ms, sequence, temperature, humidity, door_open, battery_level
                FROM coldchain.telemetry
                WHERE %s
                ORDER BY ts %s
                LIMIT ? OFFSET ?
                """.formatted(DEVICE_AND_PERIOD, newestFirst ? "DESC" : "ASC");
        return clickHouse.sql(sql)
                .params(tenantId, deviceId, from.toEpochMilli(), to.toEpochMilli(), limit, offset)
                .query((rs, rowNum) -> new TelemetryPoint(
                        Instant.ofEpochMilli(rs.getLong("ts_ms")),
                        rs.getLong("sequence"),
                        rs.getDouble("temperature"),
                        rs.getDouble("humidity"),
                        rs.getBoolean("door_open"),
                        rs.getInt("battery_level")))
                .list();
    }

    public long countReadings(String tenantId, String deviceId, Instant from, Instant to) {
        return clickHouse.sql("SELECT count() FROM coldchain.telemetry WHERE " + DEVICE_AND_PERIOD)
                .params(tenantId, deviceId, from.toEpochMilli(), to.toEpochMilli())
                .query(Long.class)
                .single();
    }

    /**
     * One page of hourly statistics. Rows of the same hour may not be merged yet by the
     * AggregatingMergeTree, so we always GROUP BY hour and merge the partial states.
     */
    public List<HourlyStat> findHourly(String tenantId, String deviceId, Instant from, Instant to,
                                       boolean newestFirst, int limit, long offset) {
        String sql = """
                SELECT toUnixTimestamp(hour) AS hour_s,
                       min(min_temp)         AS min_temp,
                       max(max_temp)         AS max_temp,
                       avgMerge(avg_temp)    AS avg_temp,
                       sum(readings)         AS readings
                FROM coldchain.telemetry_hourly
                WHERE %s
                GROUP BY hour
                ORDER BY hour %s
                LIMIT ? OFFSET ?
                """.formatted(HOURLY_DEVICE_AND_PERIOD, newestFirst ? "DESC" : "ASC");
        return clickHouse.sql(sql)
                .params(tenantId, deviceId, from.toEpochMilli(), to.toEpochMilli(), limit, offset)
                .query((rs, rowNum) -> new HourlyStat(
                        Instant.ofEpochSecond(rs.getLong("hour_s")),
                        round2(rs.getDouble("min_temp")),
                        round2(rs.getDouble("max_temp")),
                        round2(rs.getDouble("avg_temp")),
                        rs.getLong("readings")))
                .list();
    }

    public long countHours(String tenantId, String deviceId, Instant from, Instant to) {
        return clickHouse.sql("SELECT uniqExact(hour) FROM coldchain.telemetry_hourly WHERE " + HOURLY_DEVICE_AND_PERIOD)
                .params(tenantId, deviceId, from.toEpochMilli(), to.toEpochMilli())
                .query(Long.class)
                .single();
    }

    /** Raw numbers for the compliance report, computed inside ClickHouse. */
    public record ComplianceStats(long readings, long monitoredMillis, long outOfRangeMillis, long excursions,
                                  double minTemperature, double maxTemperature, double avgTemperature) {
    }

    /**
     * Computes compliance with window functions, in three steps (read the SQL inside out):
     * <ol>
     *     <li>for every reading: is it out of range, and when is the NEXT reading
     *         ({@code leadInFrame})?</li>
     *     <li>each reading "covers" the time until the next one (not counted if the gap is longer
     *         than {@link #MAX_GAP}); also look at whether the PREVIOUS reading was out of range
     *         ({@code lagInFrame})</li>
     *     <li>sum it up: covered time, covered time out of range, and the number of excursions =
     *         readings that are out of range while the previous one was not.</li>
     * </ol>
     */
    public ComplianceStats compliance(String tenantId, String deviceId, Instant from, Instant to,
                                      double minTemp, double maxTemp) {
        String sql = """
                SELECT count()                                          AS readings,
                       sum(covered_ms)                                  AS monitored_ms,
                       sumIf(covered_ms, out_of_range)                  AS out_of_range_ms,
                       countIf(out_of_range AND NOT prev_out_of_range)  AS excursions,
                       min(temperature)                                 AS min_temp,
                       max(temperature)                                 AS max_temp,
                       avg(temperature)                                 AS avg_temp
                FROM (
                    SELECT temperature,
                           out_of_range,
                           lagInFrame(out_of_range, 1, false)
                               OVER (ORDER BY ts ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS prev_out_of_range,
                           if(dateDiff('millisecond', ts, next_ts) <= ?, dateDiff('millisecond', ts, next_ts), 0) AS covered_ms
                    FROM (
                        SELECT ts,
                               temperature,
                               (temperature < ? OR temperature > ?) AS out_of_range,
                               leadInFrame(ts, 1, ts)
                                   OVER (ORDER BY ts ROWS BETWEEN CURRENT ROW AND 1 FOLLOWING) AS next_ts
                        FROM coldchain.telemetry
                        WHERE %s
                    )
                )
                """.formatted(DEVICE_AND_PERIOD);
        return clickHouse.sql(sql)
                .params(MAX_GAP.toMillis(), minTemp, maxTemp, tenantId, deviceId, from.toEpochMilli(), to.toEpochMilli())
                .query((rs, rowNum) -> new ComplianceStats(
                        rs.getLong("readings"),
                        rs.getLong("monitored_ms"),
                        rs.getLong("out_of_range_ms"),
                        rs.getLong("excursions"),
                        rs.getDouble("min_temp"),
                        rs.getDouble("max_temp"),
                        rs.getDouble("avg_temp")))
                .single();
    }

    /** Two decimals: sensor precision, and it hides floating-point noise such as 4.8100000000000005. */
    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
