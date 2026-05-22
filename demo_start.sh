#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-abcdefghijklmnopqrstuvwxyz123456}"
TENANT_ID="${TENANT_ID:-demo}"

cd "$COMPOSE_DIR"

wait_for_autho() {
  echo "Waiting for Autho health endpoint..."
  for _ in $(seq 1 90); do
    if curl -fsS "$BASE_URL/health" >/dev/null; then
      curl -fsS "$BASE_URL/health"
      echo
      return 0
    fi
    sleep 2
  done

  echo "Autho did not become healthy in time." >&2
  docker compose logs --tail=120 autho >&2
  return 1
}

curl_json() {
  curl -fsS "$@" \
    -H "Content-Type: application/json" \
    -H "Authorization: X-API-Key $API_KEY" \
    -H "X-Tenant-ID: $TENANT_ID"
}

create_demo_policies() {
  echo "Creating demo policies..."

  curl_json -X PUT "$BASE_URL/v1/policies/DossierDemo" -d '{
    "resourceClass": "DossierDemo",
    "strategy": "almost_one_allow_no_deny",
    "rules": [
      {
        "name": "ALLOW-DEMO-CLIENT-READ-INTERNAL",
        "operation": "lire",
        "priority": 10,
        "effect": "allow",
        "conditions": [
          ["=", "$s.client-id", "001"],
          ["diff", "$r.classification", "secret"]
        ]
      },
      {
        "name": "DENY-SECRET",
        "operation": "lire",
        "priority": 100,
        "effect": "deny",
        "conditions": [
          ["=", "$r.classification", "secret"]
        ]
      }
    ],
    "tests": [
      {
        "name": "client demo lit un dossier interne",
        "subject": {"id": "001", "class": "Person", "client-id": "001"},
        "resource": {"id": "DOS-001", "class": "DossierDemo", "classification": "internal"},
        "operation": "lire",
        "expect": "allow"
      },
      {
        "name": "client demo ne lit pas un dossier secret",
        "subject": {"id": "001", "class": "Person", "client-id": "001"},
        "resource": {"id": "DOS-002", "class": "DossierDemo", "classification": "secret"},
        "operation": "lire",
        "expect": "deny"
      }
    ]
  }' >/dev/null

  curl_json -X PUT "$BASE_URL/v1/policies/FacturePurposeDemo" -d '{
    "resourceClass": "FacturePurposeDemo",
    "strategy": "almost_one_allow_no_deny",
    "schema": {
      "subjects": {"Person": ["client-id"]},
      "resources": {"FacturePurposeDemo": ["id"]},
      "contexts": {"Context": ["purpose", "requestingUser"]},
      "operations": ["process", "lire"]
    },
    "rules": [
      {
        "name": "ALLOW-DEMO-CLIENT-AGGREGATE",
        "operation": "process",
        "priority": 10,
        "effect": "allow",
        "conditions": [
          ["=", ["Person", "$s", "client-id"], "001"],
          ["=", ["Context", "$c", "purpose"], "aggregate_invoice_total"]
        ]
      },
      {
        "name": "DENY-DEMO-CLIENT-EXPORT",
        "operation": "process",
        "priority": 100,
        "effect": "deny",
        "conditions": [
          ["=", ["Person", "$s", "client-id"], "001"],
          ["=", ["Context", "$c", "purpose"], "export_invoice_details"]
        ]
      }
    ],
    "tests": [
      {
        "name": "agregation autorisee",
        "subject": {"id": "001", "class": "Person", "client-id": "001"},
        "resource": {"id": "FAC-001", "class": "FacturePurposeDemo"},
        "operation": "process",
        "context": {"purpose": "aggregate_invoice_total", "requestingUser": "alice"},
        "expect": "allow"
      },
      {
        "name": "export refuse",
        "subject": {"id": "001", "class": "Person", "client-id": "001"},
        "resource": {"id": "FAC-002", "class": "FacturePurposeDemo"},
        "operation": "process",
        "context": {"purpose": "export_invoice_details", "requestingUser": "alice"},
        "expect": "deny"
      }
    ]
  }' >/dev/null
}

seed_audit() {
  echo "Running deterministic authorization calls..."

  echo "Decision expected: allow (DossierDemo internal)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-001", "classification": "internal"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
  echo

  echo "Decision expected: deny (DossierDemo secret)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
  echo

  echo "Decision expected before Kafka injection: deny (FAC-TEST-01 is not in RocksDB yet)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "Facture", "id": "FAC-TEST-01"},
    "operation": "lire"
  }'
  echo

  echo "Decision expected: allow (authorized purpose)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "FacturePurposeDemo", "id": "FAC-001"},
    "operation": "process",
    "context": {"purpose": "aggregate_invoice_total", "requestingUser": "alice"}
  }'
  echo

  echo "Decision expected: deny (forbidden purpose)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "FacturePurposeDemo", "id": "FAC-002"},
    "operation": "process",
    "context": {"purpose": "export_invoice_details", "requestingUser": "alice"}
  }'
  echo
}

echo "Starting full Autho demo stack..."
echo "Resetting persisted demo volumes to start without Kafka business data..."
docker compose --profile tools down --remove-orphans --volumes >/dev/null 2>&1 || true
docker compose up -d --build kafka kafka-init kafka-ui openldap phpldapadmin autho

wait_for_autho

create_demo_policies
seed_audit

cat <<EOF

Demo stack is ready.

URLs:
- Autho API:       $BASE_URL
- Admin UI:        $BASE_URL/admin/ui
- Kafka UI:        http://localhost:8090
- phpLDAPadmin:    http://localhost:8091

Credentials:
- Admin UI mode:   API Key
- API key:         $API_KEY
- Tenant:          $TENANT_ID
- LDAP login DN:   cn=admin,dc=example,dc=com
- LDAP password:   admin

Stop everything with:
  ./demo_stop.sh

Inject Kafka data and retry Kafka/RocksDB decisions with:
  ./demo_inject_kafka.sh

Reset persisted demo volumes with:
  ./demo_stop.sh --volumes
EOF
