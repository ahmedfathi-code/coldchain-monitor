# Cold Chain Monitor — Study-First Plan

## Purpose and honest baseline

This repository already contains a substantial implementation of the cold-chain monitoring project described below. Treat the current code as a **baseline to understand, review, and improve**. This study plan does not change or rewrite the Git history and does not claim that work done before this plan was authored by Ahmed.

From this point forward, Ahmed is the learner and primary author of new code: he studies each flow, writes the implementation in his own words, and revises it after review. The assistant is a tutor and reviewer: explain the idea first, offer a small example or hint, review Ahmed's attempt, point out risks, and explain existing code when asked. Do not silently replace a whole feature with generated code. If a full example is needed, label it as a teaching example and have Ahmed adapt and explain it.

## Working agreement

- Work in small learning units. Start with the problem and a diagram or plain-language walkthrough; then let Ahmed attempt the code before giving the full solution.
- For each unit: trace the existing behavior, identify one goal, write or change a small piece, review it together, and finish with a short explanation in Ahmed's own words.
- Prefer readable Java and the simplest design that meets the project requirements. Do not add features just to make the project look more advanced.
- Record durable architecture choices in `docs/decisions.md`. Put personal explanations and interview notes in `docs/study-notes.md` as learning proceeds.
- Never say a build, test, container, or feature works unless it has actually been run and checked. Do not run tests or verification commands unless Ahmed asks for verification; show him how to run them himself while studying.
- Keep code, code comments, commit messages, and README in English. Discuss and teach in Egyptian Arabic, keeping standard technical names in English.
- Use small Conventional Commits for changes Ahmed has reviewed and understands. Do not rewrite or force-push published Git history as part of the learning process.
- At the end of a learning unit, stop and let Ahmed decide whether to continue. Summarize what changed, the concept in simple language, how Ahmed can inspect or verify it, and likely interview questions with answers.
- Be clear about authorship: Ahmed should claim only the work he actually understands and contributed to. Never manufacture provenance or remove collaborators' attribution to imply false authorship.

## Current code-review priorities

The first study cycle should investigate and, if confirmed by Ahmed, address these behavior questions before polishing:

1. Sustained temperature and door-open rules infer elapsed time between readings; decide how long a gap may be before continuity can no longer be assumed.
2. Compliance reports use the device's current temperature thresholds for past data; decide how threshold changes should be represented in historical reports.
3. Deleting a device sends a configuration tombstone; check whether the offline detector can later treat a missing configuration as active and alert for the deleted device.
4. Review device lifecycle in the offline detector: it currently tracks devices after the first reading, so check how never-seen configured devices and deleted devices should behave.

These are review findings to study and verify, not assertions that have already been fixed. Preserve the current implementation until the relevant behavior is understood and the change is reviewed.

# Project: coldchain-monitor
A real-time cold-chain monitoring platform for pharmacies and medicine transport trucks.
Devices inside fridges/trucks send temperature, humidity, door state and battery level.
The system detects dangerous conditions (temperature out of range for too long, door left
open, device offline, low battery), raises alerts, notifies people on Telegram, and
produces compliance reports proving medicines were stored safely.

## Tech stack
- Java 21, latest stable Spring Boot, Maven multi-module project (parent POM with
  dependencyManagement).
- Eclipse Mosquitto (MQTT), Apache Kafka in KRaft mode (single node, no ZooKeeper),
  Kafka Streams, ClickHouse, Redis, MySQL 8 + Flyway, Spring Data JPA, Spring Security
  with JWT, springdoc-openapi (Swagger UI), JUnit 5, Testcontainers.
- Optional dev tool: a Kafka UI container.

## Repository layout
coldchain-monitor/
├── common/              # shared event contracts (TelemetryEvent, AlertEvent, DeviceConfigEvent) + JSON serdes
├── device-simulator/    # fake devices publishing to MQTT, with failure scenarios
├── ingestion-service/   # Spring Boot: MQTT -> Kafka, validation, DLQ
├── stream-processor/    # Kafka Streams: rules/alerts + latest-state writer to Redis
├── api-service/         # Spring Boot: REST API, security, reports, alert lifecycle, notifications
├── infra/
│   ├── docker-compose.yml
│   ├── clickhouse/init.sql
│   └── mosquitto/ (config + password file for service accounts)
├── docs/decisions.md
├── .github/workflows/ci.yml
├── PLAN.md, CLAUDE.md, README.md, .env.example

## Data flow
simulator -> MQTT -> ingestion-service -> Kafka [telemetry.raw]
  -> ClickHouse (via Kafka Engine table + materialized views)  : full history
  -> stream-processor rules -> Kafka [alerts] -> api-service (persist + notify)
  -> stream-processor latest-state writer -> Redis               : latest reading per device
api-service -> Kafka [device.config] (compacted) -> stream-processor (GlobalKTable of thresholds)

## Contracts
- MQTT topic: coldchain/{tenantId}/devices/{deviceId}/telemetry, QoS 1.
- Telemetry JSON: deviceId, tenantId, timestamp (ISO-8601, device event time), sequence,
  temperature (°C), humidity (%), doorOpen (boolean), batteryLevel (0-100).
- Kafka topics: telemetry.raw (key = deviceId, to keep per-device ordering),
  telemetry.dlq, alerts, device.config (compacted, key = deviceId).
- Alert event: alertId, deviceId, tenantId, type (TEMP_EXCURSION, DOOR_OPEN_TOO_LONG,
  DEVICE_OFFLINE, LOW_BATTERY), transition (OPENED or RECOVERED), severity, detectedAt,
  details (e.g. current value, threshold, duration).

## Module details

### device-simulator
- Plain Java (or Spring Boot CommandLineRunner) app; configurable number of devices,
  publish interval, and scenario per device.
- Realistic readings with small random noise around a normal fridge range (2–8 °C).
- Scenarios: NORMAL, POWER_OUTAGE (temperature rises gradually past the max), DOOR_LEFT_OPEN,
  DEVICE_OFFLINE (stops publishing), LOW_BATTERY, INVALID_PAYLOAD (to exercise the DLQ).
- Device and tenant IDs must match the seed data in api-service.

### ingestion-service
- Subscribes to the MQTT topic pattern, parses and validates messages (required fields,
  sane ranges, tenant/device IDs in payload match the topic).
- Valid -> telemetry.raw; invalid -> telemetry.dlq with the original payload and error
  reason in headers. Producer: acks=all, idempotence enabled.
- Exposes health via Spring Boot Actuator.

### stream-processor
- Kafka Streams using device event time (custom TimestampExtractor), with a sensible grace
  period for late data.
- Thresholds per device (minTemp, maxTemp, excursionMinutes, doorOpenMaxMinutes) come from
  the device.config GlobalKTable, with documented default values when no config exists.
- Rules, each emitting OPENED once when the condition starts and RECOVERED once when it
  ends (no duplicate OPENED events while the condition continues), using state stores:
  1. TEMP_EXCURSION: temperature continuously outside [minTemp, maxTemp] for at least
     excursionMinutes (default 15).
  2. DOOR_OPEN_TOO_LONG: door open continuously longer than doorOpenMaxMinutes (default 5).
  3. DEVICE_OFFLINE: no reading for 5 minutes, detected with a WALL_CLOCK_TIME punctuator
     scanning a last-seen state store.
  4. LOW_BATTERY: battery below 15%.
- Separately from the Streams topology, a Kafka consumer keeps the latest reading of each
  device in Redis (a hash per device, plus a last-seen timestamp).

### ClickHouse (infra/clickhouse/init.sql)
- Kafka Engine table consuming telemetry.raw -> materialized view -> MergeTree table
  `telemetry` (PARTITION BY month, ORDER BY (tenant_id, device_id, ts)).
- AggregatingMergeTree table with hourly min/max/avg temperature per device, fed by a
  materialized view.

### api-service
- MySQL + Flyway migrations. Entities: Tenant, AppUser (roles SUPER_ADMIN, TENANT_ADMIN,
  OPERATOR), Device (serial number, name, type FRIDGE or TRUCK, location, thresholds,
  active flag), Alert (type, severity, status OPEN/ACKNOWLEDGED/RESOLVED, openedAt,
  acknowledgedBy, acknowledgedAt, resolvedAt).
- Seed data via Flyway: a super admin, two demo tenants (two pharmacies), users per tenant,
  and devices matching the simulator. Demo credentials documented in README
  (passwords stored with BCrypt).
- Security: stateless JWT authentication, role-based authorization.
- Tenant isolation is mandatory and enforced server side: the tenantId always comes from
  the authenticated user's token, never from a request parameter, and every query is
  scoped by it. Accessing another tenant's resource returns 404.
- Creating/updating a device publishes its thresholds to device.config.
- Endpoints (all list endpoints paginated with page/size/sort, and filterable where noted):
  - POST /api/auth/login
  - Tenants CRUD (SUPER_ADMIN)
  - Devices CRUD (TENANT_ADMIN), list filterable by type and active
  - GET /api/devices/{id}/latest (from Redis)
  - GET /api/devices/{id}/telemetry?from=&to= (from ClickHouse, paginated)
  - GET /api/devices/{id}/stats/hourly?from=&to= (from the aggregated table)
  - GET /api/alerts (filter by status, type, deviceId, date range)
  - PATCH /api/alerts/{id}/acknowledge and /resolve (OPERATOR or TENANT_ADMIN)
  - GET /api/reports/compliance?deviceId=&from=&to= : minutes out of range, number of
    excursions, min/max/avg temperature, compliance percentage.
- Alert consumer: reads the alerts topic; OPENED creates an Alert, RECOVERED marks it
  resolved automatically if still open.
- Notifications: Telegram bot message on OPENED alerts, with a per device+type cooldown
  implemented in Redis (SET NX with TTL) to avoid spamming. Bot token and chat id from env
  vars; if not set, log the notification instead of failing.
- DTOs separate from entities, Bean Validation, global exception handler returning
  ProblemDetail responses, Swagger UI enabled.

## Running
- `docker compose up --build` (from infra/, or the root with -f) must start EVERYTHING:
  infrastructure with healthchecks and all app services (multi-stage Dockerfiles), with
  correct startup ordering (depends_on with service_healthy).
- The simulator starts publishing automatically.

## Testing
- Unit tests for every rule using TopologyTestDriver (start, continue, recover, offline).
- Testcontainers integration tests: ingestion (valid -> telemetry.raw, invalid -> DLQ),
  api-service (auth, and a test proving tenant A cannot read tenant B's devices or alerts).
- GitHub Actions workflow running `mvn verify` on push and pull requests.

## Study roadmap (replaces the old build-from-scratch phase checklist)

The original feature specification below remains the target behavior. The phase checklist is replaced because the repository already has the modules; the next work is to learn them and improve correctness deliberately.

### Study 0 — Get oriented

- Trace one reading from `device-simulator` through MQTT, ingestion, Kafka, ClickHouse/Redis, the stream rules, and the API.
- Draw the flow in Ahmed's own words and explain what each service owns.
- Note what has and has not been personally understood; do not begin broad refactoring yet.

### Study 1 — Java contracts and message ingestion

- Learn the records and JSON contract in `common`.
- Follow validation and MQTT-to-Kafka delivery in `ingestion-service`.
- Write a small change or improvement, explain its failure behavior, and then study the corresponding tests.

### Study 2 — Stateful event processing

- Understand the alert state machine, event time, late readings, state stores, and offline punctuator in `stream-processor`.
- Work through the sustained-condition gap question and deleted-device behavior.
- Add or adjust focused tests only as part of a user-requested verification step.

### Study 3 — API, data access, and tenant security

- Trace a request through controller, service, repository, and response DTO.
- Study tenant scoping, roles, JWT, refresh tokens, and alert lifecycle.
- Explain why a cross-tenant lookup returns 404 and review security boundaries before changing them.

### Study 4 — Storage, reporting, and reliable publishing

- Learn the ClickHouse tables/views, Redis latest-reading hash, MySQL migrations, and compliance query.
- Decide how historical thresholds should be preserved for past reports.
- Trace the transactional outbox from database commit through Kafka acknowledgement and retry.

### Study 5 — Operations and portfolio readiness

- Read Docker Compose, health checks, CI, smoke test, Prometheus, Grafana, and the architecture decisions.
- Verify locally only when Ahmed asks; otherwise provide reproducible steps for him to run.
- Update README claims to match verified behavior, known limits, and Ahmed's real contributions.

For every study unit, choose one concrete change, have Ahmed author it, review it together, and stop before starting the next unit. Do not attempt a wholesale rewrite just to make the repository appear newly authored.
