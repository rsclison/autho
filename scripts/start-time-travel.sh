#!/bin/bash
# Quick start script for Time-Travel Authorization feature

set -e

echo "=== Autho Time-Travel Authorization Setup ==="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Step 1: Check if Kafka is running
echo -e "${YELLOW}[1/5]${NC} Checking Kafka availability..."
if docker ps | grep -q autho-kafka; then
    echo -e "${GREEN}✓${NC} Kafka is running"
else
    echo -e "${RED}✗${NC} Kafka is not running. Starting with docker-compose..."
    docker-compose up -d
    echo "Waiting for Kafka to be ready (30s)..."
    sleep 30
fi
echo ""

# Step 2: Create history topics
echo -e "${YELLOW}[2/5]${NC} Creating history topics..."
if [ -f "scripts/create-history-topics.sh" ]; then
    chmod +x scripts/create-history-topics.sh
    ./scripts/create-history-topics.sh
else
    echo -e "${RED}✗${NC} Script not found: scripts/create-history-topics.sh"
    exit 1
fi
echo ""

# Step 3: Check dependencies
echo -e "${YELLOW}[3/5]${NC} Installing Leiningen dependencies..."
if command -v lein &> /dev/null; then
    lein deps
    echo -e "${GREEN}✓${NC} Dependencies installed"
else
    echo -e "${RED}✗${NC} Leiningen not found. Please install: https://leiningen.org/"
    exit 1
fi
echo ""

# Step 4: Start Mirror Service
echo -e "${YELLOW}[4/5]${NC} Starting Topic Mirror Service..."
echo "This will run in the background and mirror compacted topics to history topics"

# Check if already running
if pgrep -f "autho.topic-mirror" > /dev/null; then
    echo -e "${YELLOW}⚠${NC}  Mirror service is already running"
    echo "To restart, run: pkill -f autho.topic-mirror && lein run -m autho.topic-mirror &"
else
    nohup lein run -m autho.topic-mirror > logs/topic-mirror.log 2>&1 &
    MIRROR_PID=$!
    echo -e "${GREEN}✓${NC} Mirror service started (PID: $MIRROR_PID)"
    echo "Logs: tail -f logs/topic-mirror.log"
fi
echo ""

# Step 5: Verify setup
echo -e "${YELLOW}[5/5]${NC} Verifying setup..."
sleep 3

# Check topics
echo "Checking history topics..."
TOPICS=$(docker exec autho-kafka kafka-topics --list --bootstrap-server localhost:9092 | grep -E "(invoices|contracts|legal-commitments)-history" || true)
if [ -n "$TOPICS" ]; then
    echo -e "${GREEN}✓${NC} History topics created:"
    echo "$TOPICS" | sed 's/^/  - /'
else
    echo -e "${RED}✗${NC} No history topics found"
fi
echo ""

# Check mirror service
if pgrep -f "autho.topic-mirror" > /dev/null; then
    echo -e "${GREEN}✓${NC} Mirror service is running"
else
    echo -e "${RED}✗${NC} Mirror service is not running"
fi
echo ""

echo "=== Setup Complete! ==="
echo ""
echo "Next steps:"
echo "1. Start the Autho server: lein run"
echo "2. Test time-travel endpoints (see docs/TIME_TRAVEL_AUTHORIZATION.md)"
echo ""
echo "Example time-travel query:"
echo "  curl -X POST http://localhost:3000/isAuthorized-at-time \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -H 'Authorization: Bearer \$JWT_TOKEN' \\"
echo "    -d '{"
echo "      \"timestamp\": \"2024-11-01T10:00:00Z\","
echo "      \"subject\": {\"id\": \"alice@company.com\"},"
echo "      \"action\": \"view\","
echo "      \"resource\": {\"class\": \"Facture\", \"id\": \"INV-123\"}"
echo "    }'"
echo ""
echo "Monitoring:"
echo "  - Mirror logs: tail -f logs/topic-mirror.log"
echo "  - Kafka UI: http://localhost:8090"
echo "  - List processes: ps aux | grep autho"
echo ""
echo "To stop mirror service: pkill -f autho.topic-mirror"
