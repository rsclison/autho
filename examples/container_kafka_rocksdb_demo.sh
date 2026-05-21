#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
API_KEY="${API_KEY:-abcdefghijklmnopqrstuvwxyz123456}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

cd "$COMPOSE_DIR"

echo "Starting Autho demo stack..."
docker compose up -d --build kafka kafka-init kafka-ui openldap phpldapadmin autho

echo "Waiting for Autho health endpoint..."
for _ in $(seq 1 60); do
  if curl -fsS "$BASE_URL/health" >/dev/null; then
    break
  fi
  sleep 2
done
curl -fsS "$BASE_URL/health"
echo

echo "Producing deterministic Facture objects to Kafka..."
docker compose --profile tools build kafka-producer
docker compose --profile tools run --rm \
  -v "$ROOT_DIR/docker/kafka-producer/test-factures.json:/data/test-factures.json:ro" \
  kafka-producer \
  --bootstrap kafka:29092 \
  --file /data/test-factures.json

echo "Waiting for the Autho Kafka consumer to update RocksDB..."
sleep 5

echo "Decision expected: allow (Paul service1, seuil 50000, FAC-TEST-01 montant 30000)"
curl -fsS -X POST "$BASE_URL/v1/authz/decisions" \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key $API_KEY" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "Facture", "id": "FAC-TEST-01"},
    "operation": "lire"
  }'
echo

echo "Decision expected: deny (same service, but FAC-TEST-02 montant 80000 > seuil 50000)"
curl -fsS -X POST "$BASE_URL/v1/authz/decisions" \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key $API_KEY" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "Facture", "id": "FAC-TEST-02"},
    "operation": "lire"
  }'
echo

echo "Explanation for the allowed decision:"
curl -fsS -X POST "$BASE_URL/v1/authz/explain" \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key $API_KEY" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "Facture", "id": "FAC-TEST-01"},
    "operation": "lire"
  }'
echo

cat <<EOF

Demo stack is running.
- Autho:      $BASE_URL
- Admin UI:   $BASE_URL/admin/ui
- Kafka UI:   http://localhost:8090
- LDAP UI:    http://localhost:8091

Stop it with:
  cd "$COMPOSE_DIR" && docker compose down

Reset all persisted demo data with:
  cd "$COMPOSE_DIR" && docker compose down -v
EOF
