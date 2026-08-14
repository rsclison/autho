#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-abcdefghijklmnopqrstuvwxyz123456}"
TENANT_ID="${TENANT_ID:-demo}"

cd "$COMPOSE_DIR"

wait_for_autho() {
  echo "Attente du point de santé d’Autho…"
  for _ in $(seq 1 90); do
    if curl -fsS "$BASE_URL/health" >/dev/null 2>&1; then
      curl -fsS "$BASE_URL/health"
      echo
      return 0
    fi
    sleep 2
  done

  echo "Autho n’est pas devenu disponible à temps." >&2
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
  echo "Création des politiques de démonstration…"

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

  curl_json -X PUT "$BASE_URL/v1/policies/DocumentPartageDemo" -d '{
    "resourceClass": "DocumentPartageDemo",
    "strategy": "almost_one_allow_no_deny",
    "rules": [
      {
        "name": "ALLOW-READ-VIA-BUSINESS-RELATION",
        "operation": "lire",
        "priority": 10,
        "effect": "allow",
        "conditions": [["relation", "$s", "can-read", "$r"]]
      }
    ],
    "tests": [
      {
        "name": "membre du groupe finance lit le document partage",
        "subject": {"id": "001", "class": "Person"},
        "resource": {"id": "DOC-PARTAGE-001", "class": "DocumentPartageDemo"},
        "operation": "lire",
        "expect": "allow"
      },
      {
        "name": "un document sans relation est refuse",
        "subject": {"id": "001", "class": "Person"},
        "resource": {"id": "DOC-PARTAGE-REFUSE", "class": "DocumentPartageDemo"},
        "operation": "lire",
        "expect": "deny"
      }
    ]
  }' >/dev/null

  curl_json -X PUT "$BASE_URL/v1/relations/rewrites/can-read" -d '{
    "relations": ["viewer"]
  }' >/dev/null
}

show_auditability_story() {
  echo "Chapitre 1 : auditabilité et preuve"
  echo "Génération de décisions, puis export et vérification machine d’un bundle d’évidence signé."
  echo "L’API key est liée côté serveur au sujet LDAP Person 001 ; le corps de requête reste minimal."

  echo "Décision attendue : allow (DossierDemo interne)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-001", "classification": "internal"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
  echo

  echo "Décision attendue : deny (DossierDemo secret)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
  echo

  echo "Décision attendue avant injection Kafka : deny (FAC-TEST-01 n’est pas encore dans RocksDB)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "Facture", "id": "FAC-TEST-01"},
    "operation": "lire"
  }'
  echo

  echo "Décision attendue : allow (finalité autorisée)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "FacturePurposeDemo", "id": "FAC-001"},
    "operation": "process",
    "context": {"purpose": "aggregate_invoice_total", "requestingUser": "alice"}
  }'
  echo

  echo "Décision attendue : deny (finalité interdite)"
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "FacturePurposeDemo", "id": "FAC-002"},
    "operation": "process",
    "context": {"purpose": "export_invoice_details", "requestingUser": "alice"}
  }'
  echo

  echo "Les décisions ci-dessus sont maintenant dans le journal d’audit."
  show_evidence_bundle
}

show_evidence_bundle() {
  echo "Export et vérification d’une evidence signée"
  local evidence_bundle_file
  evidence_bundle_file="$(mktemp)"
  trap 'rm -f "${evidence_bundle_file:-}"' RETURN

  echo "Export du bundle signé pour DossierDemo…"
  curl_json "$BASE_URL/v1/evidence?resourceClass=DossierDemo&subject-id=001&limit=1" | tee "$evidence_bundle_file"
  echo

  echo "Vérification du même bundle signé…"
  curl_json -X POST "$BASE_URL/v1/evidence/verify" -d @"$evidence_bundle_file"
  echo
}

show_impact_simulation() {
  echo "Chapitre 2 : analyse d’impact avant déploiement"
  curl_json -X POST "$BASE_URL/v1/policies/DossierDemo/impact" -d '{
    "candidatePolicy": {
      "resourceClass": "DossierDemo",
      "strategy": "almost_one_allow_no_deny",
      "rules": [
        {
          "name": "ALLOW-DEMO-CLIENT-READ-INTERNAL",
          "operation": "lire",
          "priority": 10,
          "effect": "allow",
          "conditions": [
            ["=", "$s.client-id", "002"],
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
      ]
    },
    "requests": [
      {
        "subject": {"id": "001", "class": "Person", "client-id": "001"},
        "resource": {"id": "DOS-001", "class": "DossierDemo", "classification": "internal"},
        "operation": "lire"
      },
      {
        "subject": {"id": "001", "class": "Person", "client-id": "001"},
        "resource": {"id": "DOS-002", "class": "DossierDemo", "classification": "secret"},
        "operation": "lire"
      }
    ]
  }'
  echo
}

show_kafka_mode_preview() {
  echo "Chapitre 3 (aperçu) : Kafka alimente les projections d’objets et de relations"
  echo "Avant injection, FAC-TEST-01 est refusée car ses attributs ne sont pas encore disponibles."
  curl_json -X POST "$BASE_URL/v1/authz/decisions" -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "Facture", "id": "FAC-TEST-01"},
    "operation": "lire"
  }'
  echo
}

echo "Démarrage de la stack complète Autho…"
echo "Réinitialisation des volumes de démonstration pour partir sans projection Kafka…"
docker compose --profile tools down --remove-orphans --volumes >/dev/null 2>&1 || true
docker compose up -d --build kafka kafka-init kafka-ui openldap phpldapadmin autho

wait_for_autho

create_demo_policies
show_auditability_story
show_impact_simulation
show_kafka_mode_preview

cat <<EOF

La stack de démonstration est prête.

URLs:
- Autho API:       $BASE_URL
- Admin UI:        $BASE_URL/admin/ui
- Kafka UI:        http://localhost:8090
- phpLDAPadmin:    http://localhost:8091

Credentials:
- Mode Admin UI :   API Key
- API key :         $API_KEY
- Tenant :          $TENANT_ID
- LDAP login DN :   cn=admin,dc=example,dc=com
- LDAP password :   admin

Arrêter l’ensemble avec :
  ./demo_stop.sh

Injecter les objets métier et les projections relationnelles Kafka avec :
  ./demo_inject_kafka.sh

Reset persisted demo volumes with:
  ./demo_stop.sh --volumes
EOF
