#!/bin/bash
set -uo pipefail

# Only run in Claude Code on the web (remote environment)
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  echo "Skipping session start hook (not in remote environment)"
  exit 0
fi

echo "=== Autho Session Start Hook ==="
echo "Initializing Leiningen environment..."

# Check if lein wrapper exists
if [ ! -f "./lein" ]; then
  echo "⚠️  WARNING: ./lein wrapper not found!"
  echo "Session starting anyway, but tests may not work"
  exit 0
fi

# Make sure lein is executable
chmod +x ./lein

# Try to download project dependencies, but don't fail if it doesn't work
echo "Downloading project dependencies (this may take a moment)..."
if ./lein deps 2>&1; then
  echo "=== Session Start Complete ==="
  echo "✅ Dependencies installed successfully"
  echo "You can now run: ./lein test"
else
  echo "=== Session Start Complete (with warnings) ==="
  echo "⚠️  WARNING: Failed to download dependencies"
  echo "This might be due to network restrictions or GitHub rate limiting"
  echo "You can try manually running: ./lein deps"
  echo "Session will continue anyway..."
fi

# Always exit successfully so Claude Desktop can start
exit 0
