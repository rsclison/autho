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

echo "Stopping Autho demo stack..."
docker compose "${DOWN_ARGS[@]}"

echo "Removing any remaining named demo containers..."
docker rm -f \
  autho-server \
  autho-kafka \
  autho-kafka-init \
  autho-kafka-ui \
  autho-ldap \
  autho-ldap-ui \
  >/dev/null 2>&1 || true

echo "Demo stack stopped."
