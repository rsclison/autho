#!/usr/bin/env bash
# Démonstration des fonctions d'audit d'Autho.
# Prérequis : ./demo_start.sh (et idéalement ./demo_inject_kafka.sh) déjà exécutés.
#
# Usage : ./demo_audit.sh [--with-truncation]
#   --with-truncation  termine par la suppression de la dernière entrée du journal
#                      (le journal reste alors invalide jusqu'à ./demo_stop.sh --volumes)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-abcdefghijklmnopqrstuvwxyz123456}"
TENANT_ID="${TENANT_ID:-demo}"
AUTHO_CONTAINER="${AUTHO_CONTAINER:-autho-server}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-autho-kafka}"
AUDIT_JDBC_URL="jdbc:h2:/data/auditdb;AUTO_SERVER=TRUE"
WITH_TRUNCATION="${1:-}"

command -v jq >/dev/null || { echo "jq est requis pour cette démonstration." >&2; exit 1; }

api() {
  curl -fsS "$@" \
    -H "Content-Type: application/json" \
    -H "Authorization: X-API-Key $API_KEY" \
    -H "X-Tenant-ID: $TENANT_ID"
}

# Variante qui renvoie le corps même en cas de code HTTP d'erreur (409, 4xx…)
api_any() {
  curl -sS "$@" \
    -H "Content-Type: application/json" \
    -H "Authorization: X-API-Key $API_KEY" \
    -H "X-Tenant-ID: $TENANT_ID"
}

now_utc() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }

chapter() { echo; echo "════════ $* ════════"; }
step() { echo; echo "▶ $*"; }

h2_sql() {
  # Accès direct à la base d'audit, comme le ferait un administrateur malveillant
  local jar
  jar="$(docker exec "$AUTHO_CONTAINER" sh -c 'ls /root/.m2/repository/com/h2database/h2/*/h2-*.jar | head -1')"
  docker exec "$AUTHO_CONTAINER" java -cp "$jar" org.h2.tools.Shell \
    -url "$AUDIT_JDBC_URL" -user sa -password "" -sql "$1" >/dev/null
}

kafka_publish() {
  # $1 = clé, $2 = objet JSON (sur une ligne)
  printf '%s|%s\n' "$1" "$2" | docker exec -i "$KAFKA_CONTAINER" \
    /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 \
    --topic business-objects-compacted --property parse.key=true --property key.separator='|' >/dev/null
}

echo "Vérification qu’Autho est disponible…"
curl -fsS "$BASE_URL/health" >/dev/null && echo "OK"

# ─────────────────────────────────────────────────────────────────────────────
chapter "1. Un journal de décisions vérifiable"

step "Santé de l’écriture d’audit (échecs visibles, AUDIT_REQUIRED possible)"
api "$BASE_URL/admin/audit/health" | jq '{healthy, writesSucceeded, writesFailed, pendingWrites}'

step "Dernières décisions DossierDemo enregistrées"
api "$BASE_URL/admin/audit/search?resourceClass=DossierDemo&pageSize=3" \
  | jq '.items[] | {id, ts, subject_id, resource_id, operation, decision, matched_rules}'

step "Vérification complète de la chaîne (contenu, chaînage, HMAC, tête de chaîne)"
VERIFY="$(api "$BASE_URL/admin/audit/verify")"
echo "$VERIFY" | jq '{valid, total, headId, headHash}'
ANCHOR_ID="$(echo "$VERIFY" | jq -r '.headId')"
ANCHOR_HASH="$(echo "$VERIFY" | jq -r '.headHash')"
echo "Ancre conservée hors de la base : id=$ANCHOR_ID hash=${ANCHOR_HASH:0:16}…"

# ─────────────────────────────────────────────────────────────────────────────
chapter "2. Une falsification en base est détectée"

TARGET_ID="$(api "$BASE_URL/admin/audit/search?resourceClass=DossierDemo&decision=deny&pageSize=1" | jq -r '.items[0].id')"
step "Un administrateur transforme en base le refus #$TARGET_ID en autorisation (hashes laissés intacts)"
h2_sql "UPDATE AUDIT_LOG SET decision = 'allow' WHERE id = $TARGET_ID"
api "$BASE_URL/admin/audit/verify" | jq '{valid, "broken-at", reason}'

step "La valeur d’origine est restaurée : la chaîne redevient valide"
h2_sql "UPDATE AUDIT_LOG SET decision = 'deny' WHERE id = $TARGET_ID"
api "$BASE_URL/admin/audit/verify" | jq '{valid, total}'

# ─────────────────────────────────────────────────────────────────────────────
chapter "3. Historique des accès à une ressource"

step "POST /audit-trail pour DossierDemo/DOS-002 (du plus ancien au plus récent)"
api -X POST "$BASE_URL/audit-trail" -d "{
  \"resourceClass\": \"DossierDemo\",
  \"resourceId\": \"DOS-002\",
  \"startTime\": \"$(date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ)\",
  \"endTime\": \"$(date -u -d '1 day' +%Y-%m-%dT%H:%M:%SZ)\"
}" | jq '{total, events: [.events[] | {timestamp, subject, action, decision, plane, matchedRules}]}'

step "Les vérifications ReBAC directes sont aussi journalisées (plane=rebac)"
api "$BASE_URL/admin/audit/search?plane=rebac&pageSize=3" \
  | jq '{total, items: [.items[] | {ts, subject_id, resource_class, resource_id, operation, decision}]}'

# ─────────────────────────────────────────────────────────────────────────────
chapter "4. Une preuve signée, vérifiable hors ligne"

step "Clé publique de signature des bundles d’évidence"
api "$BASE_URL/v1/evidence/public-key" | jq '.data // .'

BUNDLE_FILE="$(mktemp --suffix=.json)"
TAMPERED_FILE="$(mktemp --suffix=.json)"
trap 'rm -f "$BUNDLE_FILE" "$TAMPERED_FILE"' EXIT

step "Export d’un bundle signé (HMAC + Ed25519) pour DossierDemo"
api "$BASE_URL/v1/evidence?resourceClass=DossierDemo&limit=5" > "$BUNDLE_FILE"
jq '(.data // .) | {format, auditChain: (.auditChain | {valid, total, headId}), integrity: (.integrity | {algorithm, payloadSha256, publicKeySignature: (.publicKeySignature | {algorithm, keyId})})}' "$BUNDLE_FILE"

step "Vérification côté serveur"
api -X POST "$BASE_URL/v1/evidence/verify" -d @"$BUNDLE_FILE" \
  | jq '(.data // .) | {valid, signatureValid, publicKeySignatureValid, errors}'

step "Vérification hors ligne par un auditeur : clé publique seule, sans aucun secret"
docker cp "$BUNDLE_FILE" "$AUTHO_CONTAINER:/tmp/bundle.json"
docker cp "$ROOT_DIR/docker/demo-keys/evidence-signing.pub.pem" "$AUTHO_CONTAINER:/tmp/evidence.pub.pem"
docker exec "$AUTHO_CONTAINER" ./lein run -m autho.evidence-cli /tmp/bundle.json /tmp/evidence.pub.pem || true

step "Le même bundle modifié d’un seul champ est rejeté hors ligne"
sed 's/"DossierDemo"/"DossierFalsifie"/' "$BUNDLE_FILE" > "$TAMPERED_FILE"
docker cp "$TAMPERED_FILE" "$AUTHO_CONTAINER:/tmp/bundle-tampered.json"
docker exec "$AUTHO_CONTAINER" ./lein run -m autho.evidence-cli /tmp/bundle-tampered.json /tmp/evidence.pub.pem || true

# ─────────────────────────────────────────────────────────────────────────────
chapter "5. Rétention sans perte de vérifiabilité"

CUTOFF="$(api "$BASE_URL/admin/audit/search?order=asc&pageSize=2" | jq -r '.items[1].ts')"
step "Purge des entrées antérieures à $CUTOFF (début de la chaîne)"
api_any -X POST "$BASE_URL/admin/audit/purge" -d "{\"before\": \"$CUTOFF\"}" | jq '.'

step "La chaîne restante se vérifie à partir du checkpoint signé"
api "$BASE_URL/admin/audit/verify" | jq '{valid, total, checkpoint}'

# ─────────────────────────────────────────────────────────────────────────────
chapter "6. Time-travel : rejouer une décision à une date passée"

echo "Scénario : alice (AgentDemo) approuve la note de frais NF-1 (800 €)."
echo "Elle est promue manager, la politique est durcie, puis elle est rétrogradée."

step "Politique NoteFraisDemo v1 : un manager approuve jusqu’à son plafond"
api -X PUT "$BASE_URL/v1/policies/NoteFraisDemo" -d '{
  "resourceClass": "NoteFraisDemo",
  "strategy": "almost_one_allow_no_deny",
  "rules": [{
    "name": "APPROUVER-SOUS-PLAFOND",
    "operation": "approuver",
    "priority": 10,
    "effect": "allow",
    "conditions": [
      ["=", ["AgentDemo", "$s", "role"], "manager"],
      ["<=", ["NoteFraisDemo", "$r", "montant"], ["AgentDemo", "$s", "plafond"]]
    ]
  }]
}' >/dev/null && echo "v1 déployée"

kafka_publish "NF-1" '{"class":"NoteFraisDemo","id":"NF-1","montant":800}'
kafka_publish "alice" '{"class":"AgentDemo","id":"alice","role":"employe","plafond":0}'
sleep 4; T1="$(now_utc)"; echo "T1 = $T1 : alice est employée"

kafka_publish "alice" '{"class":"AgentDemo","id":"alice","role":"manager","plafond":1000}'
sleep 4; T2="$(now_utc)"; echo "T2 = $T2 : alice est manager (plafond 1000 €)"

api -X PUT "$BASE_URL/v1/policies/NoteFraisDemo" -d '{
  "resourceClass": "NoteFraisDemo",
  "strategy": "almost_one_allow_no_deny",
  "rules": [{
    "name": "APPROUVER-SOUS-PLAFOND-500",
    "operation": "approuver",
    "priority": 10,
    "effect": "allow",
    "conditions": [
      ["=", ["AgentDemo", "$s", "role"], "manager"],
      ["<=", ["NoteFraisDemo", "$r", "montant"], ["AgentDemo", "$s", "plafond"]],
      ["<=", ["NoteFraisDemo", "$r", "montant"], "500"]
    ]
  }]
}' >/dev/null
sleep 2; T3="$(now_utc)"; echo "T3 = $T3 : politique v2, approbation limitée à 500 €"

kafka_publish "alice" '{"class":"AgentDemo","id":"alice","role":"employe","plafond":0}'
sleep 4; T4="$(now_utc)"; echo "T4 = $T4 : alice est rétrogradée"

for T in "$T1" "$T2" "$T3" "$T4"; do
  step "alice pouvait-elle approuver NF-1 à $T ?"
  api_any -X POST "$BASE_URL/isAuthorized-at-time" -d "{
    \"timestamp\": \"$T\",
    \"subject\": {\"class\": \"AgentDemo\", \"id\": \"alice\"},
    \"resource\": {\"class\": \"NoteFraisDemo\", \"id\": \"NF-1\"},
    \"action\": \"approuver\"
  }" | jq '{decision, matchedRules, role: .subject.role, plafond: .subject.plafond, policy, warnings}'
done

step "Qui pouvait approuver NF-1 à T2 ?"
api_any -X POST "$BASE_URL/who-was-authorized-at" -d "{
  \"resourceClass\": \"NoteFraisDemo\", \"resourceId\": \"NF-1\",
  \"action\": \"approuver\", \"timestamp\": \"$T2\"
}" | jq '{policy, "authorized-subjects", evaluated, warnings}'

step "Que pouvait approuver alice à T2 ?"
api_any -X POST "$BASE_URL/what-could-access-at" -d "{
  \"subjectId\": \"alice\", \"subjectClass\": \"AgentDemo\",
  \"resourceClass\": \"NoteFraisDemo\", \"action\": \"approuver\", \"timestamp\": \"$T2\"
}" | jq '{"accessible-resources", evaluated, warnings}'

# ─────────────────────────────────────────────────────────────────────────────
if [[ "$WITH_TRUNCATION" == "--with-truncation" ]]; then
  chapter "7. Suppression des dernières entrées"
  LAST_ID="$(api "$BASE_URL/admin/audit/verify" | jq -r '.headId')"
  step "Suppression en base de la dernière entrée (#$LAST_ID)"
  h2_sql "DELETE FROM AUDIT_LOG WHERE id = $LAST_ID"
  api "$BASE_URL/admin/audit/verify" | jq '{valid, reason}'
  step "Vérification avec l’ancre conservée hors de la base au chapitre 1 (id=$ANCHOR_ID) : elle révèle la suppression même si la tête de chaîne en base est aussi réécrite"
  api "$BASE_URL/admin/audit/verify?anchorId=$ANCHOR_ID&anchorHash=$ANCHOR_HASH" | jq '{valid, reason}'
  echo "Le journal reste invalide : réinitialiser avec ./demo_stop.sh --volumes puis ./demo_start.sh"
fi

echo
echo "Démonstration audit terminée."
