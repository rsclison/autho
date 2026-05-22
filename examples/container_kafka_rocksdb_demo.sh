#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "This script is kept for compatibility."
echo "Use the single supported demo launcher instead: ./demo_start.sh"
echo

exec "$ROOT_DIR/demo_start.sh"
