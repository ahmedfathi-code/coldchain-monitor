#!/bin/sh
# Creates the Kafka topics used by coldchain-monitor. Safe to run more than once.
set -e

BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-kafka:9092}"
TOPICS_CMD=/opt/kafka/bin/kafka-topics.sh

# Usage: create_topic <name> <partitions> [extra kafka-topics.sh arguments]
create_topic() {
  name="$1"
  partitions="$2"
  shift 2
  "$TOPICS_CMD" --bootstrap-server "$BOOTSTRAP_SERVER" --create --if-not-exists \
    --topic "$name" --partitions "$partitions" --replication-factor 1 "$@"
}

# Valid telemetry, key = deviceId (all readings of one device go to the same partition).
create_topic telemetry.raw 3
# Invalid telemetry (dead letter queue), low volume.
create_topic telemetry.dlq 1
# Alert OPENED / RECOVERED events, key = deviceId.
create_topic alerts 3
# Device thresholds. Compacted: Kafka keeps (at least) the latest value per deviceId.
create_topic device.config 1 --config cleanup.policy=compact

echo "Topics:"
"$TOPICS_CMD" --bootstrap-server "$BOOTSTRAP_SERVER" --list
