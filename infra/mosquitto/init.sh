#!/bin/sh
# Creates the Mosquitto password file from environment variables, then starts the broker.
# This way no password (or password hash) is ever committed to the repository.
set -e

: "${MQTT_SIMULATOR_PASSWORD:?MQTT_SIMULATOR_PASSWORD must be set}"
: "${MQTT_INGESTION_PASSWORD:?MQTT_INGESTION_PASSWORD must be set}"

PASSWORD_FILE=/mosquitto/auth/passwd
ACL_FILE=/mosquitto/auth/acl
mkdir -p /mosquitto/auth

# -c creates a new file (overwriting the old one), -b takes the password from the command line.
mosquitto_passwd -c -b "$PASSWORD_FILE" simulator "$MQTT_SIMULATOR_PASSWORD"
mosquitto_passwd -b "$PASSWORD_FILE" ingestion "$MQTT_INGESTION_PASSWORD"

# The ACL is mounted read-only and owned by the host user; copy it so we can fix its owner.
cp /mosquitto/config/acl "$ACL_FILE"

# Mosquitto runs as the "mosquitto" user and wants these files owned by it and private.
chown mosquitto:mosquitto "$PASSWORD_FILE" "$ACL_FILE" /mosquitto/data
chmod 0700 "$PASSWORD_FILE" "$ACL_FILE"

exec mosquitto -c /mosquitto/config/mosquitto.conf
