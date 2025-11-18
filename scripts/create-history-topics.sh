#!/bin/bash
# Script to create history topics with retention policy
# These topics store complete event history for time-travel queries

set -e

KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
RETENTION_DAYS="${RETENTION_DAYS:-365}"
RETENTION_MS=$((RETENTION_DAYS * 24 * 60 * 60 * 1000))

echo "=== Creating Kafka History Topics ==="
echo "Broker: $KAFKA_BROKER"
echo "Retention: $RETENTION_DAYS days ($RETENTION_MS ms)"
echo ""

# Function to create a history topic
create_history_topic() {
    local topic_name=$1
    echo "Creating topic: $topic_name"

    docker exec autho-kafka kafka-topics \
        --create \
        --if-not-exists \
        --bootstrap-server localhost:9092 \
        --topic "$topic_name" \
        --partitions 3 \
        --replication-factor 1 \
        --config cleanup.policy=delete \
        --config retention.ms=$RETENTION_MS \
        --config segment.ms=86400000 \
        --config compression.type=lz4

    echo "✓ Topic $topic_name created"
    echo ""
}

# Create history topics
create_history_topic "invoices-history"
create_history_topic "contracts-history"
create_history_topic "legal-commitments-history"

echo "=== Verifying Topics ==="
docker exec autho-kafka kafka-topics \
    --list \
    --bootstrap-server localhost:9092 | grep -E "(invoices|contracts|legal-commitments)"

echo ""
echo "=== Topic Configurations ==="
for topic in invoices-history contracts-history legal-commitments-history; do
    echo "--- $topic ---"
    docker exec autho-kafka kafka-topics \
        --describe \
        --bootstrap-server localhost:9092 \
        --topic "$topic"
    echo ""
done

echo "✓ All history topics created successfully!"
echo ""
echo "Next steps:"
echo "1. Start the mirror service: lein run -m autho.topic-mirror"
echo "2. Producers can continue using compacted topics unchanged"
echo "3. History topics will be populated automatically"
