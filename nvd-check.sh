#!/usr/bin/env bash
# nvd-check.sh — Lance l'analyse CVE sur les dépendances d'Autho
#
# Usage :
#   ./nvd-check.sh                          # sans clé API (lent, rate-limité)
#   NVD_API_TOKEN=<clé> ./nvd-check.sh      # avec clé API NVD (recommandé)
#
# Obtenir une clé gratuite : https://nvd.nist.gov/developers/request-an-api-key
# Rapport HTML généré dans : target/nvd/dependency-check-report.html

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

CYAN='\033[0;36m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'

echo -e "${CYAN}[nvd]${NC} Calcul du classpath Autho..."
CLASSPATH=$(./lein with-profile -user,-dev classpath 2>/dev/null)

if [[ -z "$CLASSPATH" ]]; then
  echo -e "${RED}[nvd]${NC} Impossible de calculer le classpath. Lancez d'abord: ./lein deps" >&2
  exit 1
fi

[[ -n "${NVD_API_TOKEN:-}" ]] \
  && echo -e "${GREEN}[nvd]${NC} NVD_API_TOKEN défini — rate-limiting réduit" \
  || echo -e "${YELLOW}[nvd]${NC} NVD_API_TOKEN absent — scan ralenti par le rate-limiting NVD"

echo -e "${CYAN}[nvd]${NC} Lancement de l'analyse CVE (nvd-clojure 5.3.0)..."
echo -e "${CYAN}[nvd]${NC} Rapport : target/nvd/dependency-check-report.html"
echo ""

cd "$SCRIPT_DIR/nvd-helper"
lein with-profile -user run -m nvd.task.check \
  nvd-clojure.edn \
  "$CLASSPATH"
