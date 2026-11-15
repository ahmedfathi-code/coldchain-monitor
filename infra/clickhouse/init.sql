-- ClickHouse schema for coldchain-monitor.
-- Runs automatically ONCE, on the first start of the container with an empty data volume
-- (docker-entrypoint-initdb.d). After changing it: docker compose down -v, then up again.
--
-- Data flow:
--   Kafka topic telemetry.raw
--     -> telemetry_queue      (Kafka engine: a consumer, stores nothing)
--     -> telemetry_mv         (materialized view: converts and inserts every consumed block)
--     -> telemetry            (MergeTree: the full history)
--     -> telemetry_hourly_mv  (materialized view: aggregates every inserted block)
--     -> telemetry_hourly     (AggregatingMergeTree: min / max / avg temperature per hour)

CREATE DATABASE IF NOT EXISTS coldchain;

-- 1. Kafka engine table. Reading from it consumes the topic, so we never query it directly:
--    the materialized view below is its only reader. Column names match the JSON fields.
--    The timestamp is read as text and converted in the view (ISO-8601 with a "Z" suffix).
CREATE TABLE IF NOT EXISTS coldchain.telemetry_queue
(
    deviceId     String,
    tenantId     String,
    timestamp    String,
    sequence     UInt64,
    temperature  Float64,
    humidity     Float64,
    doorOpen     Bool,
    batteryLevel UInt8
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'telemetry.raw',
    kafka_group_name = 'clickhouse-telemetry',
    kafka_format = 'JSONEachRow',
    -- The ingestion service already validated every message; if a broken one still slips
    -- through, skip it instead of blocking the whole topic.
    kafka_skip_broken_messages = 10;

-- 2. Full history. One row per reading.
--    PARTITION BY month: old months can be dropped cheaply (retention) and queries on a
--    date range only open the matching partitions.
--    ORDER BY (tenant_id, device_id, ts): rows of one device are stored together and sorted
--    by time, which is exactly how we query ("device X between from and to").
CREATE TABLE IF NOT EXISTS coldchain.telemetry
(
    tenant_id     LowCardinality(String),
    device_id     LowCardinality(String),
    ts            DateTime64(3, 'UTC'),
    sequence      UInt64,
    temperature   Float64,
    humidity      Float64,
    door_open     Bool,
    battery_level UInt8
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, device_id, ts);

-- 3. Moves every block consumed from Kafka into the history table.
CREATE MATERIALIZED VIEW IF NOT EXISTS coldchain.telemetry_mv TO coldchain.telemetry AS
SELECT
    tenantId                                        AS tenant_id,
    deviceId                                        AS device_id,
    parseDateTime64BestEffort(timestamp, 3, 'UTC')  AS ts,
    sequence,
    temperature,
    humidity,
    doorOpen                                        AS door_open,
    batteryLevel                                    AS battery_level
FROM coldchain.telemetry_queue;

-- 4. Hourly statistics per device.
--    min and max can be combined directly (the min of several mins is the overall min), so a
--    SimpleAggregateFunction is enough. An average cannot: the average of averages is wrong
--    when the groups have different sizes. avgState stores (sum, count) instead, and
--    avgMerge combines them correctly at query time.
CREATE TABLE IF NOT EXISTS coldchain.telemetry_hourly
(
    tenant_id  LowCardinality(String),
    device_id  LowCardinality(String),
    hour       DateTime('UTC'),
    min_temp   SimpleAggregateFunction(min, Float64),
    max_temp   SimpleAggregateFunction(max, Float64),
    avg_temp   AggregateFunction(avg, Float64),
    readings   SimpleAggregateFunction(sum, UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (tenant_id, device_id, hour);

-- 5. Aggregates every block inserted into telemetry (a view can read from a table that is
--    itself filled by another view).
CREATE MATERIALIZED VIEW IF NOT EXISTS coldchain.telemetry_hourly_mv TO coldchain.telemetry_hourly AS
SELECT
    tenant_id,
    device_id,
    toStartOfHour(ts)     AS hour,
    min(temperature)      AS min_temp,
    max(temperature)      AS max_temp,
    avgState(temperature) AS avg_temp,
    count()               AS readings
FROM coldchain.telemetry
GROUP BY tenant_id, device_id, hour;

-- Example query for the hourly stats (rows of the same hour may not be merged yet, so
-- always GROUP BY and merge):
--   SELECT hour, min(min_temp), max(max_temp), avgMerge(avg_temp), sum(readings)
--   FROM coldchain.telemetry_hourly
--   WHERE tenant_id = 'nile-pharmacy' AND device_id = 'nile-fridge-01'
--   GROUP BY hour ORDER BY hour;
