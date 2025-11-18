#!/bin/bash
# Demonstration of Time-Travel Authorization capabilities
# This script simulates a real-world audit scenario

set -e

API_URL="${API_URL:-http://localhost:3000}"
JWT_TOKEN="${JWT_TOKEN:-your-jwt-token-here}"
API_KEY="${API_KEY:-your-api-key-here}"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo "=== Time-Travel Authorization Demo ==="
echo "API URL: $API_URL"
echo ""

# Scenario: Security Incident Investigation
echo -e "${BLUE}=== SCENARIO: Security Incident Investigation ===${NC}"
echo "A sensitive invoice (INV-99999) was leaked on November 1st, 2024 at 03:00 AM"
echo "We need to find out who had access at that time"
echo ""

# Query 1: Who was authorized at the time of the incident?
echo -e "${YELLOW}Query 1:${NC} Who was authorized to view INV-99999 at the time of incident?"
echo ""

curl -s -X POST "$API_URL/who-was-authorized-at" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "resourceClass": "Facture",
    "resourceId": "INV-99999",
    "action": "view",
    "timestamp": "2024-11-01T03:00:00Z"
  }' | jq '.'

echo ""
echo "---"
echo ""

# Query 2: What could Alice access at that time?
echo -e "${YELLOW}Query 2:${NC} What documents could alice@company.com access at 03:00 AM?"
echo ""

curl -s -X POST "$API_URL/what-could-access-at" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "subjectId": "alice@company.com",
    "action": "view",
    "timestamp": "2024-11-01T03:00:00Z"
  }' | jq '.'

echo ""
echo "---"
echo ""

# Query 3: Complete audit trail for the leaked invoice
echo -e "${YELLOW}Query 3:${NC} Complete audit trail for INV-99999 in October 2024"
echo ""

curl -s -X POST "$API_URL/audit-trail" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "resourceClass": "Facture",
    "resourceId": "INV-99999",
    "startTime": "2024-10-01T00:00:00Z",
    "endTime": "2024-10-31T23:59:59Z"
  }' | jq '.'

echo ""
echo "---"
echo ""

# Query 4: Verify specific authorization at past time
echo -e "${YELLOW}Query 4:${NC} Was alice@company.com authorized to view INV-99999 at 03:00 AM?"
echo ""

curl -s -X POST "$API_URL/isAuthorized-at-time" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "timestamp": "2024-11-01T03:00:00Z",
    "subject": {"id": "alice@company.com"},
    "action": "view",
    "resource": {"class": "Facture", "id": "INV-99999"}
  }' | jq '.'

echo ""
echo "---"
echo ""

# Scenario 2: GDPR Compliance Audit
echo -e "${BLUE}=== SCENARIO: GDPR Compliance Audit ===${NC}"
echo "Customer requests to know who accessed their personal data in 2024"
echo ""

echo -e "${YELLOW}Query 5:${NC} Audit trail for customer data access"
echo ""

curl -s -X POST "$API_URL/audit-trail" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "resourceClass": "DonnéesClient",
    "resourceId": "CUSTOMER-12345",
    "startTime": "2024-01-01T00:00:00Z",
    "endTime": "2024-12-31T23:59:59Z"
  }' | jq '.'

echo ""
echo "---"
echo ""

# Scenario 3: Post-Promotion Verification
echo -e "${BLUE}=== SCENARIO: Post-Promotion Verification ===${NC}"
echo "Bob was promoted to Manager on Oct 1st, 2024"
echo "Verify he got the correct access rights after promotion"
echo ""

echo -e "${YELLOW}Query 6:${NC} What could bob@company.com access after promotion?"
echo ""

curl -s -X POST "$API_URL/what-could-access-at" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "subjectId": "bob@company.com",
    "action": "approve",
    "timestamp": "2024-10-01T09:00:00Z"
  }' | jq '.'

echo ""
echo "---"
echo ""

# Scenario 4: Compare access before and after
echo -e "${BLUE}=== SCENARIO: Compare Access Before/After Event ===${NC}"
echo "Compare Bob's access rights before and after promotion"
echo ""

echo -e "${YELLOW}Query 7a:${NC} Bob's access BEFORE promotion (Sep 30th)"
curl -s -X POST "$API_URL/what-could-access-at" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "subjectId": "bob@company.com",
    "action": "approve",
    "timestamp": "2024-09-30T23:59:00Z"
  }' | jq '.accessible-resources | length'

echo ""

echo -e "${YELLOW}Query 7b:${NC} Bob's access AFTER promotion (Oct 1st)"
curl -s -X POST "$API_URL/what-could-access-at" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{
    "subjectId": "bob@company.com",
    "action": "approve",
    "timestamp": "2024-10-01T09:00:00Z"
  }' | jq '.accessible-resources | length'

echo ""
echo "---"
echo ""

echo -e "${GREEN}=== Demo Complete! ===${NC}"
echo ""
echo "This demo showed:"
echo "  1. Forensic investigation of security incident"
echo "  2. GDPR compliance audit trail"
echo "  3. Post-promotion access verification"
echo "  4. Before/after comparison of access rights"
echo ""
echo "All queries use historical data without affecting production systems!"
echo ""
echo "For more examples, see: docs/TIME_TRAVEL_AUTHORIZATION.md"
