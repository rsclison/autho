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

echo "Vérification qu’Autho est disponible…"
curl -fsS "$BASE_URL/health"
echo

echo "Publication des données déterministes dans Kafka…"
echo "Les factures alimentent RocksDB ; les relations alimentent la projection ReBAC."
echo "Construction du producteur Kafka…"
docker compose --profile tools build kafka-producer

echo "Publication des objets Facture…"
docker compose --profile tools run --rm \
  -v "$ROOT_DIR/docker/kafka-producer/test-factures.json:/data/test-factures.json:ro" \
  kafka-producer \
  --bootstrap kafka:29092 \
  --file /data/test-factures.json

echo "Publication des événements relationnels depuis les outbox de démonstration…"
docker compose --profile tools run --rm \
  -v "$ROOT_DIR/docker/kafka-producer/test-relations.json:/data/test-relations.json:ro" \
  kafka-producer \
  --bootstrap kafka:29092 \
  --topic authorization-relationships \
  --file /data/test-relations.json

echo "Attente des consommateurs Kafka d’Autho…"
sleep 5

echo "Vidage des caches pour forcer une évaluation avec les données fraîches…"
curl -fsS -X DELETE "$BASE_URL/v1/cache" \
  -H "Authorization: X-API-Key $API_KEY" \
  -H "X-Tenant-ID: $TENANT_ID"
echo

echo "Décision attendue : allow (FAC-TEST-01 montant 30000 < seuil LDAP 50000)"
curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
  "subject": {"id": "ignored-with-api-key", "class": "Person"},
  "resource": {"class": "Facture", "id": "FAC-TEST-01"},
  "operation": "lire"
}'
echo

echo "Décision attendue : deny (FAC-TEST-02 montant 80000 > seuil LDAP 50000)"
curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
  "subject": {"id": "ignored-with-api-key", "class": "Person"},
  "resource": {"class": "Facture", "id": "FAC-TEST-02"},
  "operation": "lire"
}'
echo

echo "Check relationnel attendu : true (Person 001 -> groupe -> dossier parent, via rewrite can-read)"
curl_json -X POST "$BASE_URL/v1/relations/check" -d '{
  "subject": {"class": "Person", "id": "001"},
  "relation": "can-read",
  "resource": {"class": "DocumentPartageDemo", "id": "DOC-PARTAGE-001"}
}'
echo

echo "Décision ReBAC attendue : allow (DocumentPartageDemo)"
curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
  "subject": {"id": "ignored-with-api-key", "class": "Person"},
  "resource": {"class": "DocumentPartageDemo", "id": "DOC-PARTAGE-001"},
  "operation": "lire"
}'
echo

echo "Décision ReBAC attendue : deny (document sans relation)"
curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
  "subject": {"id": "ignored-with-api-key", "class": "Person"},
  "resource": {"class": "DocumentPartageDemo", "id": "DOC-PARTAGE-REFUSE"},
  "operation": "lire"
}'
echo

echo "État opérationnel de la projection relationnelle :"
curl_json "$BASE_URL/v1/relations/status"
echo

echo "Réconciliation en lecture seule de la source demo-iam (résultat attendu : aucun écart) :"
curl_json -X POST "$BASE_URL/v1/relations/reconcile" -d '{
  "source": "demo-iam",
  "tuples": [
    {
      "subject": {"class": "Person", "id": "001"},
      "relation": "member",
      "resource": {"class": "Group", "id": "finance-demo"},
      "sourceVersion": "1"
    },
    {
      "subject": {"class": "Group", "id": "finance-demo"},
      "relation": "viewer",
      "resource": {"class": "Folder", "id": "workspace-demo"},
      "sourceVersion": "1"
    }
  ]
}'
echo

cat <<EOF

Les données de démonstration ont été injectées.

Vous pouvez maintenant montrer :
- Kafka UI : les topics business-objects-compacted et authorization-relationships ;
- Admin UI > Données PIP : les factures dans RocksDB ;
- Admin UI > Relations : tuples projetés, santé Kafka, journal et rapport de réconciliation ;
- Admin UI > Audit : les décisions Facture et DocumentPartageDemo.
EOF
