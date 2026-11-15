#!/usr/bin/env bash
# Checks that a running stack works end to end, through the public API only:
#   simulator -> MQTT -> ingestion -> Kafka -> ClickHouse / Redis -> api-service
#
# Usage (after "docker compose -f infra/docker-compose.yml up -d --build --wait"):
#   bash infra/smoke-test.sh
# Needs only curl. Waits up to TIMEOUT_SECONDS for the first readings to arrive.
set -euo pipefail

API="${API_URL:-http://localhost:8080}"
PROMETHEUS="${PROMETHEUS_URL:-http://localhost:9090}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"

fail() { echo "FAIL: $*" >&2; exit 1; }

# Retries a command until it succeeds or the timeout is reached.
wait_for() {
  local description="$1"; shift
  local deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  until "$@" > /dev/null 2>&1; do
    [ "$(date +%s)" -lt "$deadline" ] || fail "timed out waiting for: $description"
    sleep 5
  done
  echo "OK:   $description"
}

# The actuator is on an internal port; the public API is up when it serves its OpenAPI docs.
api_is_up() { curl -sf "$API/v3/api-docs" > /dev/null; }

login() {
  curl -sf -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin@nile-pharmacy.local","password":"Coldchain#2026"}' \
    | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'
}

get() { curl -sf "$API$1" -H "Authorization: Bearer $TOKEN"; }

has_devices() { get "/api/devices" | grep -q '"id":"nile-fridge-01"'; }
has_latest_reading() { get "/api/devices/nile-fridge-01/latest" | grep -q '"temperature"'; }
has_history() { get "/api/devices/nile-fridge-01/telemetry" | grep -qE '"totalElements":[1-9]'; }
# All three services are scraped successfully ("health":"up" once per target).
metrics_are_scraped() { [ "$(curl -sf "$PROMETHEUS/api/v1/targets?state=active" | grep -o '"health":"up"' | wc -l)" -eq 3 ]; }
isolates_tenants() { [ "$(curl -s -o /dev/null -w '%{http_code}' "$API/api/devices/delta-fridge-01" \
  -H "Authorization: Bearer $TOKEN")" = "404" ]; }

wait_for "api-service is healthy" api_is_up
TOKEN="$(login)" || fail "login failed"
[ -n "$TOKEN" ] || fail "login returned no token"
echo "OK:   login returns a JWT"
wait_for "devices are listed (MySQL)" has_devices
wait_for "latest reading is available (MQTT -> ingestion -> Kafka -> Redis)" has_latest_reading
wait_for "history is stored (Kafka -> ClickHouse)" has_history
wait_for "another tenant's device answers 404" isolates_tenants
wait_for "Prometheus scrapes ingestion, stream-processor and api-service" metrics_are_scraped
echo "Smoke test passed."
