#!/usr/bin/env sh
# Publishes key|json lines to the platform events topic through the Kafka container of docker compose.
# Usage: ./scripts/publish-events.sh [file] [topic]
set -eu

FILE="${1:-$(dirname "$0")/../demo/platform-events.jsonl}"
TOPIC="${2:-platform.events.v1}"

grep -v '^[[:space:]]*$' "$FILE" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:29092 \
  --topic "$TOPIC" \
  --property parse.key=true \
  --property "key.separator=|"

echo "Published $(grep -c -v '^[[:space:]]*$' "$FILE") events to $TOPIC"
