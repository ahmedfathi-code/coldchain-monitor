# Architecture decision records

Short records of notable decisions: context, decision, alternatives, consequences.
Newest decisions are appended at the bottom.

---

## ADR-001: Spring Boot 4.1 with Jackson 3

**Context.** The spec asks for the latest stable Spring Boot. At the time of Phase 0 that is
4.1.1 (4.2 is still a milestone). Spring Boot 4 uses Jackson 3 (package `tools.jackson`)
by default and still ships Jackson 2 only for compatibility.

**Decision.** Use `spring-boot-starter-parent` 4.1.1 as the parent of the root POM, and
Jackson 3 in the `common` module. All library versions (Kafka 4.2.1, Jackson 3.1,
JUnit 6, Testcontainers 2) come from the Spring Boot BOM.

**Alternatives.** Spring Boot 3.5 with Jackson 2: more tutorials online, but not the latest
version and closer to end of support. Importing `spring-boot-dependencies` as a BOM instead
of using the parent: more flexible, but we would have to configure plugins ourselves.

**Consequences.** One place (the parent version) controls every library version. Jackson 3
has small API differences from Jackson 2 (unchecked `JacksonException`, `JsonMapper.builder()`,
ISO-8601 dates by default), which are worth knowing for interviews.

---

## ADR-002: `common` is a plain Java library with our own JSON serdes

**Context.** Four services exchange the same events (telemetry, alerts, device config).
They must agree on the JSON format exactly.

**Decision.** `common` contains Java records for the events, enums, topic names, one shared
`JsonMapper` (`EventJson`) and small Kafka `JsonSerializer` / `JsonDeserializer` /
`JsonSerdes` classes. It depends only on Jackson and `kafka-clients` (for the interfaces),
not on Spring.

**Alternatives.** Spring Kafka's `JacksonJsonSerializer`: works, but ties the Kafka Streams
code and the simulator to Spring Kafka, and adds type headers we do not need. Avro/Protobuf
with a schema registry: stronger schema evolution, but a lot more infrastructure and
concepts for a project of this size.

**Consequences.** Every module reads/writes identical JSON. Deserialization errors are
wrapped in Kafka's `SerializationException`, so standard Kafka error handling applies.
`null` is kept as `null` so tombstones work on the compacted `device.config` topic.
Unknown JSON fields are ignored (a newer producer does not break an older consumer).

---

## ADR-003: Wrapper types in `TelemetryEvent`

**Context.** Devices can send incomplete payloads. The ingestion service must reject a
message with a missing field.

**Decision.** `TelemetryEvent` uses `Long`, `Double`, `Boolean`, `Integer` instead of
primitives, so a missing field is `null` and can be detected by validation.

**Alternatives.** Primitives with Jackson's `FAIL_ON_NULL_FOR_PRIMITIVES` /
`FAIL_ON_MISSING_CREATOR_PROPERTIES`: rejects bad messages too, but with generic Jackson
error messages instead of clear validation reasons for the DLQ headers.

**Consequences.** Consumers downstream of the ingestion service can rely on the fields
being non-null (validation guarantees it), but must remember they are wrapper types.

---

## ADR-004: Alert severities are WARNING and CRITICAL

**Context.** The alert contract has a `severity` field but the spec does not list values.

**Decision.** Two levels: `WARNING` (needs attention, medicines not yet at risk, e.g. low
battery) and `CRITICAL` (medicines may be damaged, e.g. temperature excursion). The exact
mapping from rule to severity is decided in Phase 2.

**Alternatives.** Three or more levels (INFO/WARNING/CRITICAL): more flexibility, but no
rule in the spec needs INFO.

**Consequences.** Simple to reason about and to display. Adding a level later only means
adding an enum constant.

---

## ADR-005: Maven wrapper committed

**Context.** The build must work on a machine without Maven on the PATH (for example a
Windows laptop) and in Docker builds / CI.

**Decision.** Commit the Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper`), pinned to
Maven 3.9.11. `.gitattributes` forces LF endings for `mvnw` and `*.sh` files so they still
work when checked out on Windows.

**Alternatives.** Require a local Maven installation: one more prerequisite, and version
drift between developers.

**Consequences.** `./mvnw verify` (or `mvnw.cmd verify`) works anywhere with only a JDK.

---

## ADR-006: Docker Compose configuration and dev defaults

**Context.** `docker compose up` must work from a fresh clone with one command, but the spec
also says: no real secrets in the repo. Docker Compose reads the `.env` file from the
directory of the compose file (`infra/`), not from the repository root (verified with
`docker compose config`).

**Decision.**
- Every credential in `infra/docker-compose.yml` is an environment variable with a
  development-only default, e.g. `${MYSQL_PASSWORD:-coldchain_dev_password}`.
- `.env.example` at the repo root lists every variable. To override values, copy it to
  `infra/.env` (or pass `--env-file .env` when running from the root).
- Host ports are bound to `127.0.0.1` only, so the dev passwords are never reachable from
  the network.
- The compose project is named `coldchain` so containers and volumes have stable names
  regardless of the folder name.

**Alternatives.** `${VAR:?}` (fail if not set): no default secrets in the file at all, but a
fresh clone needs a manual copy step before anything starts.

**Consequences.** One-command start works out of the box. The defaults are obviously
dev-only values; production would inject real secrets through the platform's secret store.

Note: ClickHouse recommends raising the open-files limit (`ulimits: nofile: 262144`). We do
not set it: Docker Desktop and GitHub Actions already default to a higher limit, and on
hosts with a lower hard limit the setting makes the container fail to start.

---

## ADR-007: Mosquitto service accounts generated at startup

**Context.** The broker must reject anonymous clients and use service accounts. A committed
Mosquitto password file would contain password hashes of real credentials.

**Decision.** Two accounts with fixed, non-secret usernames: `simulator` (may only publish
telemetry) and `ingestion` (may only subscribe to telemetry). Their passwords come from
environment variables. A small startup script (`infra/mosquitto/init.sh`) generates the
password file with `mosquitto_passwd` inside the container on every start, then starts
Mosquitto. An ACL file (`infra/mosquitto/acl`) limits what each account may do.

**Alternatives.** Commit a hashed password file: simpler, but it is a secret in the repo and
changing a password means regenerating and committing the file. Anonymous access: no
security at all.

**Consequences.** No password (or hash) is committed. Changing a password only needs a new
environment variable value and a container restart.

---

## ADR-008: Kafka topics created by an init container, auto-creation disabled

**Context.** Topics need specific settings (partitions, `cleanup.policy=compact` for
`device.config`). Auto-created topics would silently get broker defaults.

**Decision.** `auto.create.topics.enable=false` on the broker. A one-shot `kafka-init`
container (same Kafka image) runs `infra/kafka/create-topics.sh` with
`kafka-topics.sh --create --if-not-exists` after the broker is healthy.
Partitions: 3 for the keyed, high-volume topics (`telemetry.raw`, `alerts`), 1 for the
low-volume topics (`telemetry.dlq`, `device.config`). Replication factor 1 (single broker).
Application services will wait for it with `condition: service_completed_successfully`.

**Alternatives.** Let each service create its topics (Spring `NewTopic` beans): topic
settings would be spread over several services. Enable auto-creation: typos in topic
names would create new topics instead of failing.

**Consequences.** Topic configuration lives in one readable script. Re-running it is safe
(`--if-not-exists`). Kafka Streams internal topics are still created by Kafka Streams
itself (it uses the admin API, not auto-creation).

---

## ADR-009: Readable string ids for tenants and devices

**Context.** The simulator's device and tenant ids must match the api-service seed data
(Phase 3), and they appear in MQTT topics, Kafka keys, Redis keys and ClickHouse rows.

**Decision.** Ids are short readable slugs, used everywhere as the business identifier:
tenants `nile-pharmacy` and `delta-pharmacy`; devices `nile-fridge-01`, `nile-truck-01`,
`delta-fridge-01`, `delta-fridge-02`. Device ids are globally unique (not only per tenant),
because Kafka and Redis key by device id alone.

**Alternatives.** Numeric database ids: meaningless in logs and topics, and they only exist
after the row is inserted. UUIDs: unique without coordination, but hard to read in a demo.

**Consequences.** Logs, topics and queries are easy to read. Phase 3 must seed exactly these
ids (the database may still add a surrogate primary key if useful).

---

## ADR-010: Eclipse Paho as MQTT client

**Context.** The simulator publishes and the ingestion service subscribes over MQTT 3.1.1.

**Decision.** Use the Eclipse Paho Java client (`org.eclipse.paho.client.mqttv3`) directly,
with its blocking `MqttClient`, automatic reconnect and a callback for incoming messages.

**Alternatives.** Spring Integration MQTT: more configuration and abstraction on top of
Paho. HiveMQ MQTT client: modern and actively developed, but a larger API (reactive/async
builders) and more dependencies (Netty, RxJava).

**Consequences.** Small, well-known API that is easy to explain. Paho 1.2.5 is stable but
no longer actively developed; switching client later only touches two small classes.

---

## ADR-011: At-least-once ingestion (acknowledge MQTT only after Kafka)

**Context.** A reading must not be lost between MQTT and Kafka, even when Kafka is down.

**Decision.**
- The ingestion service uses a persistent MQTT session (`cleanSession=false`, fixed client
  id) with QoS 1, so the broker queues messages while the service is offline.
- For every message it sends to Kafka **synchronously** (`acks=all`, idempotent producer)
  inside Paho's `messageArrived`. Paho acknowledges the message only when that method
  returns. If Kafka fails, the method throws, Paho drops the connection without
  acknowledging, reconnects automatically, and the broker redelivers the message.
- Verified by hand: with Kafka stopped for 3 minutes, a published reading was retried and
  arrived in `telemetry.raw` exactly once after Kafka came back.

**Alternatives.** Asynchronous sends with Paho manual acknowledgements: much higher
throughput, but more complex ordering and error handling. Fire-and-forget: simple but loses
data whenever Kafka is unavailable.

**Consequences.** No data loss. Duplicates are possible (Kafka stored the record but the
MQTT acknowledgement was lost), so consumers must tolerate them: the Redis writer is
idempotent; ClickHouse may store a rare duplicate row. Throughput is limited to one Kafka
round trip per message, which is plenty for this project. A fixed client id means only one
ingestion instance; scaling out would use MQTT shared subscriptions.

---

## ADR-012: Hand-written telemetry validator

**Context.** Messages must be checked for required fields, sane ranges and matching ids
between payload and topic, and the reason must be readable in the DLQ header.

**Decision.** A plain `TelemetryValidator` class that collects all problems into one
message. Limits: temperature -80..60 °C (ultra-low vaccine freezers reach -80 °C),
humidity and battery 0..100, sequence >= 0, timestamp at most 5 minutes in the future.

**Alternatives.** Jakarta Bean Validation annotations on `TelemetryEvent`: declarative,
but the topic/payload cross-check still needs custom code, and it would add a validation
dependency to the shared `common` module.

**Consequences.** All rules are in one short, unit-tested class with clear error messages.

---

## ADR-013: ClickHouse ingestion with a Kafka engine table and materialized views

**Context.** The full telemetry history must land in ClickHouse without extra services.

**Decision.**
- `telemetry_queue` (Kafka engine, `JSONEachRow`) consumes `telemetry.raw`; a materialized
  view inserts every consumed block into the `telemetry` MergeTree table. The timestamp is
  read as a string and converted with `parseDateTime64BestEffort` in the view.
- `telemetry` is `PARTITION BY toYYYYMM(ts)` and `ORDER BY (tenant_id, device_id, ts)`;
  ids are `LowCardinality(String)` (few distinct values, stored as a dictionary).
- A second view aggregates into `telemetry_hourly` (AggregatingMergeTree):
  `SimpleAggregateFunction` for min/max/sum, `AggregateFunction(avg)` for the average.
- `kafka_skip_broken_messages = 10` so a malformed record cannot block the topic.

**Alternatives.** A Kafka Connect ClickHouse sink or a custom consumer service: one more
component to run. `ReplacingMergeTree` to remove duplicates: possible later, but
deduplication only happens during background merges.

**Consequences.** Zero application code for the history. The schema runs once on the first
start (`docker-entrypoint-initdb.d`): after changing `init.sql`, recreate the volume.

---

## ADR-014: Latest state in Redis with an atomic "save if newer" script

**Context.** The API needs the latest reading per device quickly. Readings may arrive late
or be redelivered, and an older reading must never overwrite a newer one.

**Decision.** A plain Kafka consumer (`@KafkaListener`, group `latest-state-writer`)
writes one hash per device, `device:{deviceId}:latest`, via a small Lua script. The script
always updates `lastSeenAt` (when we last heard from the device) and replaces the reading
only if its event time (`timestampMillis`) is not older than the stored one. Redis runs
scripts atomically, so there is no race between the read and the write.

**Alternatives.** Plain `HSET` without a check: late data can overwrite newer data.
`WATCH`/`MULTI` optimistic transactions: also correct, but more code and retries.

**Consequences.** Correct under reordering and duplicates. The Lua logic is covered by a
Testcontainers integration test against a real Redis.

---

## ADR-015: One multi-stage Dockerfile for all services

**Context.** Four Spring Boot modules need images; they share the parent POM and `common`.

**Decision.** A single root `Dockerfile` with a `MODULE` build argument. Stage 1 (JDK)
builds with the committed Maven wrapper (`-pl MODULE -am`, tests skipped, Maven cache
mount). Stage 2 (JRE only) runs the jar as a non-root user. Compose builds each service
from it. The `maven` base image is not needed thanks to the wrapper.

**Alternatives.** One Dockerfile per module: four near-identical files. Buildpacks
(`spring-boot:build-image`): no Dockerfile at all, but less transparent for learning.

**Consequences.** One file to understand and maintain. Tests are not run during image
builds; they run with `mvn verify` locally and in CI.

---

## ADR-016: Simulator design

**Context.** The simulator must be configurable (devices, interval, scenario per device)
and produce realistic readings.

**Decision.** A Spring Boot app without a web server. Devices are listed in
`application.yml`; a `@Scheduled` task publishes one reading per device every interval.
Each `SimulatedDevice` keeps its own state; in the NORMAL scenario the temperature is
pulled 10 % back towards 5 °C on every step plus Gaussian noise, and clamped to
2.5..7.5 °C so NORMAL never triggers an alert. All devices share one MQTT connection.

**Alternatives.** Plain Java `main` with a `ScheduledExecutorService`: fewer dependencies
but hand-written configuration parsing. One MQTT connection per device: more realistic,
but more resources and no benefit for this project.

**Consequences.** Config and scheduling come for free from Spring Boot. Because the broker
sees one client, per-device connection loss is simulated by not publishing (Phase 2).

---

## ADR-017: Alert rules as custom processors sharing one state machine

**Context.** The rules are about conditions that hold *continuously* ("out of range for 15
minutes without interruption"), must emit OPENED exactly once and RECOVERED exactly once,
and must survive restarts.

**Decision.** Use the Kafka Streams Processor API with key-value state stores (one entry per
device). TEMP_EXCURSION, DOOR_OPEN_TOO_LONG and LOW_BATTERY are the same pattern ("condition
held for at least N minutes"; N = 0 for the battery), so they share
`SustainedConditionProcessor`, a three-state machine (NORMAL / PENDING / OPEN); each rule is
a small class implementing `SustainedConditionRule`. DEVICE_OFFLINE has its own processor.
All four output streams are merged into the `alerts` topic.

**Alternatives.** Windowed aggregations (e.g. max temperature per 15-minute window): windows
answer "what happened in this time bucket", not "has this been true without interruption
since X", and they would emit results per window instead of once per incident.

**Consequences.** Each rule is ~40 lines and unit-tested with TopologyTestDriver. Adding a
rule of the same shape is one new class. DOOR_OPEN_TOO_LONG fires when the door has been
open for `doorOpenMaxMinutes` *or more* (same `>=` comparison as the other rules).

---

## ADR-018: Event time, grace period and wall-clock time

**Context.** The spec asks for device event time with a grace period for late data, and for
DEVICE_OFFLINE detection with a wall-clock punctuator.

**Decision.**
- `TelemetryTimestampExtractor` makes the device timestamp the record time; every duration
  in the rules is computed from device timestamps.
- Grace period (`rules.grace-period`, default 1 minute), applied per device by
  `LateReadingFilter`: a reading more than 1 minute older than the newest reading of the
  same device is dropped (the state machines have already moved past that moment); a
  slightly late one is still used.
- DEVICE_OFFLINE uses wall-clock time on both sides: the store keeps when we *received* the
  last reading, and a `WALL_CLOCK_TIME` punctuator (every 30 s) compares it with now. Event
  time cannot be used here: it only advances when data arrives, and missing data is the
  whole point.

**Alternatives.** A reorder buffer that holds readings for the grace period and releases them
sorted by event time: exact ordering, but more code and added latency for every alert.

**Consequences.** Alert durations are exact for in-order data and accurate to within the
grace period otherwise. Caveat: if the stream processor itself is down for more than
5 minutes, all devices look offline right after it restarts, and RECOVERED follows as soon
as their next readings are processed.

---

## ADR-019: In-memory state stores with changelog topics, exactly-once processing

**Context.** The rule state is tiny (a few fields per device) but must not be lost, and an
alert must be neither lost nor duplicated after a crash.

**Decision.** All stores (including the GlobalKTable store) are `Stores.inMemoryKeyValueStore`.
Kafka Streams still writes every change to a changelog topic and rebuilds the store from it
after a restart. The application runs with `processing.guarantee=exactly_once_v2`: reading
the input, updating the stores and writing the alert are committed in one Kafka transaction.

**Alternatives.** RocksDB (the default persistent store): needed for state larger than
memory, and restores faster, but adds a native library and a local disk directory.
At-least-once processing: simpler, but a crash could produce a second OPENED event.

**Consequences.** Simple and portable (no native library on the Alpine image). With
thousands of devices a restart takes longer (full changelog replay); switching to RocksDB is
a one-line change per store.

---

## ADR-020: Demo thresholds, temporary seed script and scenario timings

**Context.** With the production defaults (15-minute excursion, 5-minute door), a demo would
take more than 20 minutes. Device configs will be managed by the api-service in Phase 3.

**Decision.** A one-shot compose container runs `infra/kafka/seed-device-config.sh`, which
publishes configs for the four demo devices to `device.config`: 2-8 °C, excursion 2 minutes,
door 1 minute. Devices without a config keep the documented defaults (2-8 °C, 15 min, 5 min).
Simulator incidents start after 1 minute and are sized for these thresholds (for example,
POWER_OUTAGE: temperature above 8 °C for about 3 minutes). Scenarios are chosen per device
with `SIMULATOR_SCENARIOS=deviceId=SCENARIO,...` because Spring Boot does not merge lists
from different property sources.

**Alternatives.** Speed up simulated time: breaks the ingestion "timestamp in the future"
check and the wall-clock offline detection. Shorter default thresholds: wrong for real use.

**Consequences.** Every scenario shows OPENED and RECOVERED within about 10 minutes. The seed
script is deleted in Phase 3.

---

## ADR-021: Notifications with a Redis cooldown and a log fallback

**Context.** OPENED alerts must reach people on Telegram without spamming them, and the
system must work without a Telegram bot.

**Decision.**
- Cooldown per device and alert type with Redis `SET key alertId NX EX 1800` (30 minutes by
  default): atomic, works across instances, and expires by itself.
- If sending fails, the cooldown key is deleted and the exception is rethrown; Spring
  Kafka's `DefaultErrorHandler` retries the record 3 times, 5 seconds apart, then logs and
  skips it so later alerts are not blocked.
- Without `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` the message is logged instead.
- The bot token is part of the request URL; it is put in the path literally (a URI variable
  would percent-encode its ":") and removed from any exception message.
- RECOVERED events are logged only (the spec notifies on OPENED); storing and resolving
  alerts comes with the database in Phase 3.
- Severities: TEMP_EXCURSION and DEVICE_OFFLINE are CRITICAL (medicines may be damaged, or
  we cannot prove they are not); DOOR_OPEN_TOO_LONG and LOW_BATTERY are WARNING.

**Alternatives.** An in-memory cooldown map: lost on restart and not shared between
instances. A Telegram client library: unnecessary for one HTTP call.

**Consequences.** At most one message per device and type per 30 minutes, even if a condition
flaps. A new consumer group reads the alerts topic from the beginning, so the very first start
may notify older alerts (at most one per device and type thanks to the cooldown).

---

## ADR-022: JWT with Spring's OAuth2 resource server and an HMAC secret

**Context.** The API must be stateless and authenticate every request with a JWT.

**Decision.** `POST /api/auth/login` checks the username and BCrypt password with Spring
Security's `AuthenticationManager` and returns a JWT signed with HS256 (claims: `sub`,
`roles`, `tenantId`, `iss`, `iat`, `exp`, 1 hour). For all other requests,
`spring-boot-starter-oauth2-resource-server` verifies signature, expiry and issuer and maps
the `roles` claim to `ROLE_*` authorities. The secret comes from `JWT_SECRET` (at least
32 characters, checked at startup).

**Alternatives.** A hand-written JWT filter with the jjwt library: more code in the most
security-sensitive place. RS256 with a key pair or an identity provider (Keycloak): better
when several services issue or verify tokens, but more infrastructure than one API needs.

**Consequences.** Almost no custom security code; Spring validates tokens. Tokens cannot be
revoked before they expire (1 hour); refresh tokens and revocation are out of scope.
*(Updated by ADR-031: access tokens now live 15 minutes and sessions continue with refresh
tokens.)*

---

## ADR-023: Tenant isolation in every query, answered with 404

**Context.** Tenant isolation is mandatory: a user must never see or change another tenant's
data, and the tenant must come from the token, never from the request.

**Decision.** Controllers take the tenant from the verified JWT (`CurrentUser.requireTenantId()`).
Every service method and repository query used by the API takes that `tenantId`
(`findByIdAndTenantId`, `search(tenantId, ...)`); ClickHouse queries also filter on
`tenant_id`. A resource of another tenant is reported as **404 Not Found**, exactly like a
resource that does not exist, so a caller cannot even learn that it exists. The SUPER_ADMIN
belongs to no tenant (enforced by a database CHECK constraint) and therefore sees no tenant
data. `ApiSecurityIT` proves it against a real database.

**Alternatives.** Hibernate filters or MySQL row-level views: isolation applied automatically,
but invisible when reading the code, and easy to switch off by mistake. 403 for another
tenant's resource: tells an attacker that the id exists.

**Consequences.** Isolation is visible in every method signature and easy to review. Device
ids are global (they are Kafka and Redis keys), so creating a device with an id or serial
number used by another tenant returns 409.

---

## ADR-024: Role rules

| Endpoint group | SUPER_ADMIN | TENANT_ADMIN | OPERATOR |
|---|---|---|---|
| Tenants CRUD | yes | - | - |
| Devices: list, get, latest, telemetry, hourly stats | - | yes | yes |
| Devices: create, update, delete | - | yes | - |
| Alerts: list, get, acknowledge, resolve | - | yes | yes |
| Compliance report | - | yes | yes |

**Context.** The spec gives "Devices CRUD (TENANT_ADMIN)" and "acknowledge/resolve
(OPERATOR or TENANT_ADMIN)"; operators also need to see devices, alerts and reports to do
their job.

**Decision.** Reads are open to TENANT_ADMIN and OPERATOR; device writes to TENANT_ADMIN only;
tenants to SUPER_ADMIN only. Rules are `@PreAuthorize` annotations on the controllers.

**Consequences.** The rule for an endpoint is written right above it. Denied calls get a
403 problem detail.

---

## ADR-025: Identifiers and alert storage

**Context.** Tenants and devices already have readable ids in MQTT, Kafka, Redis and
ClickHouse (ADR-009); alert events carry a UUID.

**Decision.** Tenant and device ids are chosen at creation (lowercase letters, digits,
hyphens) and never change. The alert row uses the `alertId` of its OPENED event as primary
key, so a redelivered event is detected and stored only once. Alerts have no foreign key to
the device: they are compliance history and must survive a device being deleted. Alert
details are stored as JSON text.

**Alternatives.** Auto-increment ids everywhere plus a separate "external id" column: two ids
per device to keep in sync.

**Consequences.** One id per device across the whole system; idempotent alert consumption.

---

## ADR-026: Publishing device thresholds inside the database transaction

**Status.** Superseded by ADR-030 (transactional outbox).

**Context.** Creating, updating or deleting a device must reach the `device.config` topic,
otherwise the rules use stale thresholds ("dual write" problem: database and Kafka).

**Decision.** The device service saves and flushes the entity, then sends the config to Kafka
and waits for the acknowledgement, all inside the `@Transactional` method. If Kafka fails,
the exception rolls the database change back. A delete sends a tombstone (null value). On
startup, `DeviceConfigSync` republishes every device (this also covers devices created by the
Flyway seed data, which replaces the Phase 2 seed script).

**Alternatives.** Transactional outbox (write an "event" row in the same transaction, a relay
publishes it): the robust solution, but one more table and a background job.

**Consequences.** Simple and consistent in practice. Rare edge case: Kafka accepted the
message but the database commit then fails; the startup sync repairs such a difference.

---

## ADR-027: ClickHouse queries and the compliance definition

**Context.** Telemetry, hourly statistics and compliance reports come from ClickHouse, while
JPA and Flyway use MySQL.

**Decision.**
- A second `DataSource` (HikariCP) with a `JdbcClient` for ClickHouse, declared with
  `@Bean(defaultCandidate = false)` so Spring Boot still auto-configures MySQL as the primary.
  Plain SQL with `?` parameters; times passed as epoch milliseconds.
- Paging with `LIMIT/OFFSET` plus a `count()`; only the time field can be sorted
  (`sort=timestamp,desc` / `sort=hour,desc`). Without `from`/`to`: the last 24 hours.
- Compliance report, computed with window functions (`leadInFrame`, `lagInFrame`):
  each reading covers the time until the next reading; gaps longer than 5 minutes are **not
  monitored** (the device was offline). Minutes out of range = covered time of out-of-range
  readings; excursions = number of separate out-of-range periods (any length);
  compliance % = in-range time / monitored time. The device's current thresholds are used.

**Alternatives.** Computing the report in Java: would stream every reading of the period
over the network. Using the stored alerts as "excursions": would only count excursions long
enough to raise an alert.

**Consequences.** One SQL query per report; covered by `TelemetryRepositoryIT` against a real
ClickHouse initialised with `infra/clickhouse/init.sql`. If thresholds changed during the
period, the report uses the current ones.

---

## ADR-028: One page format, safe sorting and error format

**Decision.** Every list returns `PageResponse` (`content`, `page`, `size`, `totalElements`,
`totalPages`) instead of Spring's `Page`, so the JSON contract does not depend on library
internals. `sort` values are checked against a list of allowed fields (400 otherwise) and
`size` is capped at 100. All errors, including the 401/403 produced by the security filters,
are RFC 9457 problem details (`application/problem+json`); validation errors list every
invalid field under `errors`.

---

## ADR-029: Test strategy and CI

**Context.** The spec asks for rule unit tests, integration tests for ingestion and for the
api-service's security, and a CI workflow running `mvn verify`; Phase 4 is done when a fresh
clone runs with one command and CI is green.

**Decision.**
- Unit tests (surefire, `*Test`) for pure logic; the alert rules run in `TopologyTestDriver`,
  with a mocked wall clock for the offline punctuator.
- Integration tests (failsafe, `*IT`) use real infrastructure through Testcontainers, never
  mocks of it: Mosquitto with the project's own config and ACL, Kafka, MySQL with the real
  Flyway migrations, Redis, and ClickHouse initialised with the real `init.sql`.
- GitHub Actions runs `./mvnw verify`, then a second job that does exactly the one-command
  quick start (`docker compose up --build --wait`) and runs `infra/smoke-test.sh`, which
  checks the pipeline through the public API. This also proves the multi-stage Dockerfile
  builds from a clean checkout.

**Alternatives.** Embedded Kafka / H2 instead of containers: faster, but they behave
differently from the real systems (H2 is not MySQL, and ClickHouse has no embedded mode).

**Consequences.** `mvn verify` needs Docker. The integration tests found two real bugs
during development (the URL encoding of the Telegram token, and custom Kafka factories that
ignored Spring Boot's connection details).

---

## ADR-030: Transactional outbox for device.config

**Context.** ADR-026 published device thresholds to Kafka inside the database transaction.
That still has gaps (Kafka accepts the message but the commit then fails; or the process dies
between the two), and it made every device write fail while Kafka was down.

**Decision.** Transactional outbox:
- `DeviceService` writes an `outbox_event` row (topic, key, JSON payload; NULL = tombstone) in
  the SAME MySQL transaction as the device change. `Outbox.add` uses
  `Propagation.MANDATORY`, so calling it without a transaction fails immediately.
- `OutboxRelay` (`@Scheduled`, every second) reads the oldest unpublished rows (only committed
  rows are visible), sends each to Kafka, waits for the acknowledgement, and sets
  `published_at`. On the first failure it records the error, increments `attempts` and stops
  the batch, so a later message for the same device can never overtake it; the next run
  retries from that row. The producer gives up after 10 s (`max.block.ms`) instead of 60 s.
- The startup sync of all devices also goes through the outbox. Published rows are deleted
  after 7 days (`outbox.retention`).

**Alternatives.** Publishing inside the transaction (ADR-026): simpler, not atomic. Change
data capture with Debezium reading the MySQL binlog: no polling and no relay code, but one
more distributed system to run. `@TransactionalEventListener(AFTER_COMMIT)` publishing
directly: the message is lost if the process dies right after the commit.

**Consequences.** A device change and its message are saved together or not at all, and
device writes keep working during a Kafka outage (verified: an update and a delete made while
Kafka was stopped were delivered, in order, seconds after it came back). Delivery is at least
once: a message can be sent twice if the app dies after Kafka accepted it but before the row
was marked, which is harmless because device configs are idempotent (latest value wins).
There is up to about a second of delay. The relay assumes a single api-service instance:
with several instances, only one should run it (for example with ShedLock or
`SELECT ... FOR UPDATE SKIP LOCKED` plus per-key ordering), otherwise two relays could publish
the same device's messages out of order. *(Solved by ADR-033: ShedLock.)*

---

## ADR-031: Rotating refresh tokens with reuse detection

**Context.** A stolen access token (JWT) cannot be revoked, so it should be short-lived; but
users should not have to type their password every few minutes.

**Decision.**
- Access tokens now live **15 minutes** (`JWT_EXPIRY`). Login also returns a **refresh token**
  valid for 7 days (`JWT_REFRESH_EXPIRY`): 32 random bytes from `SecureRandom`, Base64 URL.
- MySQL table `refresh_token` stores only the **SHA-256 hash** of each token (a database leak
  gives no usable tokens). A plain fast hash is enough because the token is 256 random bits
  and cannot be guessed; BCrypt's salt would also make lookup by value impossible.
- **Rotation:** `POST /api/auth/refresh` revokes the presented token and returns a new access
  token and a new refresh token. All tokens rotated from one login share a `family_id`
  (= one session).
- **Reuse detection:** presenting an already revoked token means two parties hold it (only
  the latest token should ever be used), so the whole family is revoked and a warning is
  logged. The revocation is kept although the request fails
  (`@Transactional(noRollbackFor = InvalidRefreshTokenException.class)`).
- The token row is read with `SELECT ... FOR UPDATE`, so two concurrent refreshes with the
  same token are serialized: exactly one succeeds, the other is seen as reuse.
- `POST /api/auth/logout` revokes the session's family. A daily job deletes expired rows.
- Refreshing checks that the user still exists and is enabled.

**Alternatives.** Long-lived JWTs only (the previous design): simple, but a leaked token works
for its whole life. Refresh tokens as JWTs: they cannot be revoked or detected when reused
without storing them anyway. Storing refresh tokens in Redis with a TTL: works, but MySQL
already holds the users, gives transactions and foreign keys, and sessions survive a Redis
flush. An `HttpOnly` cookie instead of a JSON field: better against XSS for a browser app,
but this API has no front-end yet and JSON is simpler for Swagger and curl.

**Consequences.** A stolen access token is useful for at most 15 minutes; a stolen refresh
token is detected the first time both the thief and the user refresh. A user who is disabled
keeps a working access token for up to 15 minutes (no deny-list). Clients must store the
newest refresh token after every refresh. Two browser tabs refreshing with the same token at
the same moment end their shared session (the price of strict reuse detection). A token
used after logout is handled like a reused one (refused and logged), which is harmless: its
session is already ended.

---

## ADR-032: Login rate limiting with Redis counters of failed attempts

**Context.** Without a limit, anyone can try passwords against `/api/auth/login` as fast as
the server answers (brute force, credential stuffing).

**Decision.** `LoginRateLimiter` keeps two counters of **failed** attempts in Redis:
- per IP + username (limit 5): stops guessing one account's password;
- per IP (limit 20): stops one client trying many accounts. Failed refresh-token attempts
  count here too.

Each counter lives 15 minutes from its first failure (fixed window). `INCR` and `PEXPIRE` run
in one Lua script, so a counter can never be left without an expiry. The limit is checked
**before** the password, so once blocked even the right password gets **429 Too Many
Requests** with `Retry-After` (seconds left on the counter). A successful login deletes the
IP + username counter. Limits and window are configurable (`LOGIN_*`).

**Alternatives.** Counting all requests (success included) with Bucket4j or a gateway: a
general-purpose limiter, but it can throttle legitimate traffic and adds a dependency; failed
attempts are what signal an attack. A per-username limit without the IP: an attacker could
lock any user out on purpose (denial of service). Account lockout in MySQL until an admin
unlocks it: same lockout problem, and needs an admin flow. A sliding window (sorted set per
key): more exact, more code; a fixed window is enough for this purpose. An in-memory map:
not shared between api-service instances and lost on restart.

**Consequences.** Cheap (a few Redis commands per login) and shared by every instance. Many
users behind one NAT share the per-IP limit of 20 failures. The IP is the TCP peer address;
behind a reverse proxy, `server.forward-headers-strategy` must be configured so the real
client address is used (a client-sent `X-Forwarded-For` must never be trusted directly). If
Redis is down, logins fail instead of silently losing the protection (fail closed): the
api-service uses a 2 s Redis timeout, so the client gets a 500 after 2 s instead of waiting
for the 60 s default (both measured with Redis stopped).

---

## ADR-033: ShedLock so only one instance runs the outbox relay

**Context.** Every api-service instance runs the `@Scheduled` outbox relay (ADR-030). With
two instances, both could read the same pending rows: the same message would be sent twice,
and worse, one instance could send a device's newer thresholds before the other sends the
older ones, so consumers would end up with the wrong (older) configuration.

**Decision.** ShedLock 7 (`shedlock-spring` + `shedlock-provider-jdbc-template`), with the
lock stored in MySQL (table `shedlock`, Flyway V5):
- `publishPending` has `@SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M")`. Before
  each run, an instance tries to set `lock_until` in the future on the lock row (one atomic
  `UPDATE ... WHERE lock_until <= now`); if another instance holds it, this run is skipped.
  The lock is released when the run ends, so any healthy instance can take the next run.
- `usingDbTime()`: lock times come from MySQL's clock, so clock differences between
  instances cannot give the lock to two of them.
- `lockAtMostFor` (1 minute) only matters when the holder dies mid-run: then the lock expires
  by itself and another instance takes over. For this to be safe, a run must never last longer
  than the lock, so a run stops starting new sends after 30 s (`MAX_RUN_TIME`); one send takes
  at most ~20 s (10 s `max.block.ms` + 10 s waiting for the acknowledgement).
- The cleanup jobs (old outbox rows, expired refresh tokens) are not locked: they are single
  idempotent `DELETE` statements, so running them on every instance is harmless.

**Alternatives.** `SELECT ... FOR UPDATE SKIP LOCKED` on outbox rows: instances could work in
parallel, but two instances would take different rows of the same device, so per-device
order needs extra logic (partitioning by key); parallelism is not needed at this volume. A
Redis lock (ShedLock's Redis provider or Redisson): works too, but the outbox already lives
in MySQL, so the lock sits next to the data it protects and needs no second system. Leader
election (Kubernetes lease, Spring Integration leader): more infrastructure than one job
needs. Running the relay in a separate single-replica service: one more deployable, and
"single replica" is an operational promise nobody enforces.

**Consequences.** Any number of api-service instances can run; exactly one publishes at a
time, and the relay moves between instances as they come and go. Verified with two
instances on one MySQL and Kafka: 40 updates of one device were published by both
instances in turns (18 and 22), each message exactly once and in order; after `kill -9` of the
lock holder in the middle of a run, the other instance published the pending message 53 ms
after the dead instance's lock expired. The price: after such a crash, publishing pauses for
up to 1 minute, and every instance does a small `UPDATE` on the lock row each poll interval.
Delivery stays at least once (ADR-030), which consumers already tolerate.

---

## ADR-034: Inactive devices raise no alerts

**Context.** Devices have an `active` flag in the api-service, but the stream processor did
not know about it. A decommissioned device that is switched off went silent, and five
minutes later raised a critical DEVICE_OFFLINE alert (and a Telegram message) every time.

**Decision.**
- `DeviceConfigEvent` (the `device.config` message) gets an `active` field, filled from the
  device. A message without the field (written before it existed, still on the compacted
  topic) means **active**: the record's constructor turns a missing value into `true`, so an
  upgrade can never silently switch off monitoring for every device.
- The three reading rules (`SustainedConditionProcessor`) ignore readings of an inactive
  device and delete its state for that rule. Without the delete, a condition that was
  pending when the device was deactivated would count from its old start time after
  re-activation and could open an alert at once.
- The offline detector reads the device config from the `device.config` GlobalKTable's
  store (global stores are readable from every processor) and never reports an inactive
  device offline. When a silent device is activated again, the next punctuation reports it.
- Readings of inactive devices are still stored (latest reading, ClickHouse history): only
  alerting is switched off.

**Alternatives.** Filtering inactive devices out of `telemetry.raw` in the ingestion
service: the history would be lost, and ingestion would need the device table. Joining the
offline detector's stream with the GlobalKTable: the punctuator emits alerts when NO record
arrives, so it must look the config up itself anyway. Closing open alerts when a device is
deactivated: they may still be real (the fridge really was warm), so they stay open until an
operator resolves them in the api-service. (Only an open DEVICE_OFFLINE is still closed
automatically if the device sends again; the reading rules emit nothing while inactive.)

**Consequences.** Deactivating a device stops its alerts within the seconds it takes the
config to reach the stream processor (via the outbox, ADR-030). Verified live: with
LOW_BATTERY and DEVICE_OFFLINE scenarios on two active and two deactivated devices, only
the active ones raised alerts; after re-activation both others raised theirs within 4 s.
A deleted device (tombstone) is treated like an unknown device: monitored with default
thresholds, so it should be deactivated before it is deleted.

---

## ADR-035: Metrics with Micrometer, Prometheus and a provisioned Grafana dashboard

**Context.** Logs show what happened to one message; they cannot answer "how many readings
per second do we get, is the DLQ growing, is any consumer falling behind, how many alerts
are open?" at a glance. Operators need numbers over time.

**Decision.**
- **Micrometer + Prometheus format** in the three backend services
  (`micrometer-registry-prometheus`, endpoint `/actuator/prometheus`). Spring Boot already
  provides JVM, HTTP, database pool and Kafka client metrics; every metric gets an
  `application` label.
- **A few business metrics**, each where the event happens: accepted/rejected readings in the
  ingestion service, alert events in the stream processor (a `peek` on the alert stream, so
  the topology itself stays free of Spring), and open alerts, pending outbox messages and
  login results in the api-service.
- **Labels are small fixed sets.** The DLQ reason text contains values ("temperature 999 is
  outside ..."), so the validator also returns a category (`wrong_topic`, `invalid_json`,
  `missing_fields`, `invalid_values`) used as the label. No label ever holds device ids,
  tenant ids or free text: each distinct label value is a new time series in Prometheus.
- **Actuator on an internal port in the api-service** (`management.server.port` 8081, not
  published): Prometheus reaches it inside the Docker network, while the public port 8080
  serves only the API. The stream processor gets a small web server (port 8082) only for
  actuator; this also gives it a Docker healthcheck.
- **Custom Kafka consumer factories register `MicrometerConsumerListener` themselves.**
  Spring Boot adds it only to the factories it creates (the same reason these factories
  already take the bootstrap servers from `KafkaConnectionDetails`); without it the
  api-service's consumer lag was missing.
- **Prometheus and Grafana in docker-compose**, configured from files: scrape targets in
  `infra/prometheus/prometheus.yml`, the data source and the "Coldchain overview" dashboard
  provisioned from `infra/grafana`. Anonymous read-only viewing for the demo; plugin downloads
  and update checks are off, so Grafana starts offline.

**Alternatives.** Pushing metrics (StatsD, OTLP to a collector): one more component to run;
pulling is the Prometheus default and needs no client configuration. The OpenTelemetry
Collector with traces and metrics: the better long-term direction, more setup than this
project needs today (listed as a next step). Counting alerts from the `alerts` topic in the
api-service instead of the stream processor: fine too, but the producer is the natural place
and the api-service already reports the business state (open alerts). Protecting
`/actuator/prometheus` with a token: Prometheus would need a long-lived credential; a
network-internal port is simpler and the usual setup.

**Consequences.** One command starts the stack with a working dashboard (readings/s,
rejected messages by reason, alert events, open alerts, outbox backlog, consumer lag, API
rate and p95 latency, login results, JVM heap), and the smoke test fails if Prometheus cannot
scrape any service. Gauges that count rows in MySQL run one small query per scrape (every
15 s). Under exactly-once processing, a retried transaction counts its alerts again: fine for
a rate, not an audit log (the `alerts` topic and table are the record). The api-service
health check moved to port 8081. There are no alert rules yet: someone still has to look at
the dashboard.
