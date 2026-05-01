#!/usr/bin/env bash
set -euo pipefail

echo "========================================"
echo "Compliance Checker - Environment Check"
echo "========================================"
echo ""

PASS=0
FAIL=0

check() {
  local label="$1"
  shift
  if "$@" > /dev/null 2>&1; then
    echo "  ✓ ${label}"
    PASS=$((PASS + 1))
  else
    echo "  ✗ ${label}"
    FAIL=$((FAIL + 1))
  fi
}

echo "Docker containers:"
check "PostgreSQL running" docker ps --format '{{.Names}}' | grep -q '^compliance-postgres$'
check "Ollama running" docker ps --format '{{.Names}}' | grep -q '^compliance-ollama$'
echo ""

echo "PostgreSQL:"
check "Connection" docker exec compliance-postgres pg_isready -U postgres
check "PGVector extension" docker exec compliance-postgres psql -U postgres -d compliance_db -tAc \
  "SELECT 1 FROM pg_extension WHERE extname = 'vector';" | grep -q '1'
echo ""

echo "Ollama:"
check "API responding" docker exec compliance-ollama ollama list
echo ""

echo "Installed models:"
docker exec compliance-ollama ollama list 2>/dev/null || echo "  (could not list models)"
echo ""

echo "========================================"
echo "Results: ${PASS} passed, ${FAIL} failed"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
  echo ""
  echo "Some checks failed. Run these commands to fix:"
  echo "  docker compose up -d          # start containers"
  echo "  ./infra/setup-models.sh       # pull models"
  exit 1
fi