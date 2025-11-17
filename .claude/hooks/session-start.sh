#!/bin/bash
set -euo pipefail

# Only run in Claude Code on the web (remote environment)
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  echo "Skipping session start hook (not in remote environment)"
  exit 0
fi

echo "=== Autho Session Start Hook ==="
echo "Initializing Leiningen environment..."

# Check if lein wrapper exists
if [ ! -f "./lein" ]; then
  echo "ERROR: ./lein wrapper not found!"
  exit 1
fi

# Make sure lein is executable
chmod +x ./lein

# Download all project dependencies
echo "Downloading project dependencies (this may take a moment)..."
./lein deps

echo "=== Session Start Complete ==="
echo "✅ Dependencies installed successfully"
echo "You can now run: ./lein test"
