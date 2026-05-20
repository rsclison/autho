#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export JWT_SECRET="${JWT_SECRET:-test-secret-key-32-characters-minimum}"
export API_KEY="${API_KEY:-test-api-key-32-characters-minimum}"

cd "$ROOT_DIR"

echo "== Backend tests =="
./lein test

echo "== Admin UI lint =="
cd "$ROOT_DIR/admin-ui"
npm run lint

echo "== Admin UI unit tests =="
npm test

echo "== Admin UI production build =="
npm run build

echo "== Release checks passed =="
