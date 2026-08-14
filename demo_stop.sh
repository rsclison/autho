#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
REMOVE_VOLUMES="${1:-}"

cd "$COMPOSE_DIR"

DOWN_ARGS=(--profile tools down --remove-orphans)
if [[ "$REMOVE_VOLUMES" == "--volumes" || "$REMOVE_VOLUMES" == "-v" ]]; then
  DOWN_ARGS+=(--volumes)
fi

echo "Arrêt de la stack de démonstration Autho…"
docker compose "${DOWN_ARGS[@]}"

echo "Nettoyage des éventuels conteneurs nommés restants…"
docker rm -f \
  autho-server \
  autho-kafka \
  autho-kafka-init \
  autho-kafka-ui \
  autho-ldap \
  autho-ldap-ui \
  >/dev/null 2>&1 || true

if [[ "$REMOVE_VOLUMES" == "--volumes" || "$REMOVE_VOLUMES" == "-v" ]]; then
  echo "Stack arrêtée et volumes de démonstration supprimés."
else
  echo "Stack arrêtée. Les volumes sont conservés ; utilisez --volumes pour les supprimer."
fi
