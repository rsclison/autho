#!/usr/bin/env bash
# start.sh — Lance le serveur Autho
# Usage: ./start.sh [--dev|--prod] [OPTIONS]
#
# Modes:
#   --dev   (défaut) Lance via lein run (rechargement facile, pas d'uberjar requis)
#   --prod  Lance l'uberjar compilé (nécessite: ./build.sh ou lein uberjar au préalable)
#
# Variables d'environnement supportées (toutes optionnelles) :
#   PDP_CONFIG         Chemin vers le fichier properties  (défaut: resources/pdp-prop.properties)
#   PORT               Port d'écoute                      (défaut: 8080)
#   LOG_LEVEL          Niveau de log                      (défaut: INFO)
#   MAX_REQUEST_SIZE   Taille max du corps en octets      (défaut: 1048576 = 1MB)
#   RATE_LIMIT_ENABLED Active le rate limiting            (défaut: true)
#   RATE_LIMIT_APIKEY_RPM  Limite API key (req/min)       (défaut: 10000)
#   RATE_LIMIT_JWT_RPM     Limite JWT (req/min)           (défaut: 1000)
#   RATE_LIMIT_ANON_RPM    Limite anonyme (req/min)       (défaut: 100)
#   KAFKA_ENABLED      Active Kafka / time-travel         (défaut: true)
#   KAFKA_BOOTSTRAP_SERVERS  Serveurs Kafka               (défaut: localhost:9092)
#   API_KEY            Clé API admin pour les routes /admin
#   JWT_SECRET         Secret HMAC pour valider les JWT

set -euo pipefail

# ─── Couleurs ─────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

log()  { echo -e "${CYAN}[autho]${NC} $*"; }
ok()   { echo -e "${GREEN}[autho]${NC} $*"; }
warn() { echo -e "${YELLOW}[autho]${NC} $*"; }
err()  { echo -e "${RED}[autho]${NC} $*" >&2; }

# ─── Répertoire du script ─────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ─── Mode ─────────────────────────────────────────────────────────────────────
MODE="dev"
for arg in "$@"; do
  case "$arg" in
    --dev)  MODE="dev"  ;;
    --prod) MODE="prod" ;;
    --help|-h)
      sed -n '2,25p' "$0" | sed 's/^# //'
      exit 0
      ;;
    *)
      err "Argument inconnu: $arg"
      err "Usage: $0 [--dev|--prod]"
      exit 1
      ;;
  esac
done

# ─── Config ───────────────────────────────────────────────────────────────────
export PDP_CONFIG="${PDP_CONFIG:-resources/pdp-prop.properties}"
export MAX_REQUEST_SIZE="${MAX_REQUEST_SIZE:-1048576}"
export RATE_LIMIT_ENABLED="${RATE_LIMIT_ENABLED:-true}"
export KAFKA_ENABLED="${KAFKA_ENABLED:-true}"

if [[ ! -f "$PDP_CONFIG" ]]; then
  err "Fichier de configuration introuvable: $PDP_CONFIG"
  exit 1
fi

# ─── Affichage de la config ───────────────────────────────────────────────────
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║           Autho Authorization Server         ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"
echo ""
log "Mode         : $MODE"
log "Config       : $PDP_CONFIG"
log "Port         : ${PORT:-8080}"
log "Kafka        : ${KAFKA_ENABLED}"
log "Rate limit   : ${RATE_LIMIT_ENABLED}"
[[ -n "${API_KEY:-}" ]] && ok "API_KEY      : définie" || warn "API_KEY      : non définie (routes /admin non protégées par clé)"
[[ -n "${JWT_SECRET:-}" ]] && ok "JWT_SECRET   : définie" || warn "JWT_SECRET   : non définie"
echo ""

# ─── Construction de l'UI si nécessaire ───────────────────────────────────────
if [[ ! -f "resources/public/admin/index.html" ]]; then
  warn "Admin UI non construite. Lancement du build..."
  if command -v npm &>/dev/null && [[ -d "admin-ui" ]]; then
    (cd admin-ui && npm run build)
    ok "Admin UI construite → resources/public/admin/"
  else
    warn "npm ou admin-ui/ introuvable — Admin UI ignorée"
  fi
fi

# ─── Lancement ───────────────────────────────────────────────────────────────
if [[ "$MODE" == "prod" ]]; then
  # ── Mode production : uberjar ────────────────────────────────────────────
  UBERJAR=$(ls target/autho-*-standalone.jar 2>/dev/null | sort -V | tail -1)
  if [[ -z "$UBERJAR" ]]; then
    err "Aucun uberjar trouvé dans target/. Lancez d'abord:"
    err "  ./lein uberjar"
    exit 1
  fi
  ok "Lancement production : $UBERJAR"
  exec java \
    ${PORT:+-Dserver.port="$PORT"} \
    ${LOG_LEVEL:+-Dlogback.configurationFile=logback.xml} \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -Xms256m -Xmx1g \
    -jar "$UBERJAR"
else
  # ── Mode développement : lein run ────────────────────────────────────────
  if [[ ! -f "./lein" ]]; then
    err "Script lein introuvable dans $SCRIPT_DIR"
    exit 1
  fi
  ok "Lancement développement via lein run"
  exec ./lein run
fi
