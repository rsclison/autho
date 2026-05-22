#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-abcdefghijklmnopqrstuvwxyz123456}"
TENANT_ID="${TENANT_ID:-demo}"

cd "$COMPOSE_DIR"

curl_json() {
  curl -fsS "$@" \
    -H "Content-Type: application/json" \
    -H "Authorization: X-API-Key $API_KEY" \
    -H "X-Tenant-ID: $TENANT_ID"
}

echo "Checking that Autho is running..."
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

echo "Clearing Autho caches so the post-injection decision is evaluated from fresh PIP data..."
curl -fsS -X DELETE "$BASE_URL/v1/cache" \
  -H "Authorization: X-API-Key $API_KEY" \
  -H "X-Tenant-ID: $TENANT_ID"
echo

echo "Decision expected after Kafka injection: allow (FAC-TEST-01 montant 30000 < seuil LDAP 50000)"
curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
  "subject": {"id": "ignored-with-api-key", "class": "Person"},
  "resource": {"class": "Facture", "id": "FAC-TEST-01"},
  "operation": "lire"
}'
echo

echo "Decision expected after Kafka injection: deny (FAC-TEST-02 montant 80000 > seuil LDAP 50000)"
curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
  "subject": {"id": "ignored-with-api-key", "class": "Person"},
  "resource": {"class": "Facture", "id": "FAC-TEST-02"},
  "operation": "lire"
}'
echo

cat <<EOF

Kafka demo data has been injected.

You can now show:
- Kafka UI topic: http://localhost:8090
- Admin UI audit filtered on resource class Facture: $BASE_URL/admin/ui
EOF
