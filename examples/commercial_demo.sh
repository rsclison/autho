#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-test-api-key-32-characters-minimum}"
API_CLIENT_ID="${API_CLIENT_ID:-trusted-internal-app}"

curl_json() {
  curl -sS "$@" \
    -H "X-API-Key: ${API_KEY}" \
    -H "Content-Type: application/json"
}

echo "== Health =="
curl -sS "${BASE_URL}/health"
echo

echo "== Create demo policy =="
curl_json -X PUT "${BASE_URL}/v1/policies/Facture" -d '{
  "resourceClass": "Facture",
  "strategy": "almost_one_allow_no_deny",
  "rules": [
    {
      "name": "demo-allow-same-service",
      "priority": 10,
      "operation": "lire",
      "effect": "allow",
      "conditions": [
        ["=", ["Application", "$s", "client-id"], "'${API_CLIENT_ID}'"]
      ]
    },
    {
      "name": "demo-deny-archive",
      "priority": 20,
      "operation": "lire",
      "effect": "deny",
      "conditions": [
        ["=", ["Facture", "$r", "status"], "archive"]
      ]
    }
  ]
}'
echo

echo "== Allowed decision =="
curl_json -X POST "${BASE_URL}/v1/authz/decisions" -d '{
  "subject": {"id": "client-declared-value", "class": "Application"},
  "resource": {"id": "FAC-001", "class": "Facture", "service": "comptabilite", "status": "open"},
  "operation": "lire"
}'
echo

echo "== Denied decision =="
curl_json -X POST "${BASE_URL}/v1/authz/decisions" -d '{
  "subject": {"id": "client-declared-value", "class": "Application"},
  "resource": {"id": "FAC-002", "class": "Facture", "service": "comptabilite", "status": "archive"},
  "operation": "lire"
}'
echo

echo "== Policy versions =="
curl_json "${BASE_URL}/v1/policies/Facture/versions"
echo

echo "== Cache stats =="
curl_json "${BASE_URL}/v1/cache/stats"
echo
