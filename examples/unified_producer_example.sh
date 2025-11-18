#!/bin/bash
# Example: Producing messages to the unified business-objects topic
#
# Messages must include "class" and "id" fields for routing to RocksDB Column Families
# All other fields are business attributes

KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
TOPIC="business-objects-compacted"

echo "=== Unified Topic Producer Example ==="
echo "Topic: $TOPIC"
echo "Broker: $KAFKA_BROKER"
echo ""

# Example 1: Invoice (Facture)
echo "Sending Invoice (Facture)..."
echo '{"class":"Facture","id":"INV-123","amount":5000,"currency":"EUR","status":"approved","customer":"ACME Corp"}' | \
  docker exec -i autho-kafka kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic "$TOPIC"

echo "✓ Invoice INV-123 sent"
echo ""

# Example 2: Contract (Contrat)
echo "Sending Contract (Contrat)..."
echo '{"class":"Contrat","id":"CTR-456","classification":"confidential","gdpr-compliant":true,"effective-date":"2024-01-01"}' | \
  docker exec -i autho-kafka kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic "$TOPIC"

echo "✓ Contract CTR-456 sent"
echo ""

# Example 3: Legal Commitment (EngagementJuridique)
echo "Sending Legal Commitment (EngagementJuridique)..."
echo '{"class":"EngagementJuridique","id":"LEG-789","clearance-level":"secret","jurisdiction":"EU","expiry-date":"2025-12-31"}' | \
  docker exec -i autho-kafka kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic "$TOPIC"

echo "✓ Legal Commitment LEG-789 sent"
echo ""

# Example 4: Update existing invoice (partial attributes)
echo "Updating Invoice INV-123 (partial update)..."
echo '{"class":"Facture","id":"INV-123","status":"paid","payment-date":"2024-11-18"}' | \
  docker exec -i autho-kafka kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic "$TOPIC"

echo "✓ Invoice INV-123 updated (merge-on-read will combine attributes)"
echo ""

echo "=== Messages Sent Successfully ==="
echo ""
echo "Verify messages in topic:"
echo "  docker exec autho-kafka kafka-console-consumer \\"
echo "    --bootstrap-server localhost:9092 \\"
echo "    --topic $TOPIC \\"
echo "    --from-beginning \\"
echo "    --max-messages 4"
echo ""
echo "Query from Autho server:"
echo "  curl -X POST http://localhost:3000/isAuthorized \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -H 'X-API-Key: \$API_KEY' \\"
echo "    -d '{"
echo "      \"subject\": {\"id\": \"alice@company.com\"},"
echo "      \"action\": \"view\","
echo "      \"resource\": {\"class\": \"Facture\", \"id\": \"INV-123\"}"
echo "    }'"
