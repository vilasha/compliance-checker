#!/usr/bin/env bash
set -euo pipefail

echo "========================================"
echo "Compliance Checker - Ollama Model Setup"
echo "========================================"
echo ""

# Load .env if present
if [ -f "$(dirname "$0")/../.env" ]; then
  set -a
  source "$(dirname "$0")/../.env"
  set +a
fi

CHAT_MODEL="${OLLAMA_CHAT_MODEL:-llama3.1:8b}"
EMBEDDING_MODEL="${OLLAMA_EMBEDDING_MODEL:-bge-m3}"

echo "Step 1: Checking if Ollama container is running..."
if ! docker ps --format '{{.Names}}' | grep -q '^compliance-ollama$'; then
  echo "ERROR: Ollama container is not running!"
  echo "Please run: docker compose up -d"
  exit 1
fi
echo "OK: Ollama container is running."
echo ""

echo "Step 2: Waiting for Ollama to be healthy..."
timeout=60
elapsed=0
until docker exec compliance-ollama ollama list > /dev/null 2>&1; do
  elapsed=$((elapsed + 2))
  if [ "$elapsed" -ge "$timeout" ]; then
    echo "ERROR: Ollama did not become healthy within ${timeout}s"
    exit 1
  fi
  sleep 2
done
echo "OK: Ollama is healthy."
echo ""

echo "Step 3: Pulling ${CHAT_MODEL} (Chat Model - ~4.7 GB)..."
echo "This may take 10-30 minutes on first download..."
docker exec compliance-ollama ollama pull "${CHAT_MODEL}"
echo "OK: ${CHAT_MODEL} downloaded."
echo ""

echo "Step 4: Pulling ${EMBEDDING_MODEL} (Embedding Model - ~2.4 GB)..."
echo "This may take 10-20 minutes on first download..."
docker exec compliance-ollama ollama pull "${EMBEDDING_MODEL}"
echo "OK: ${EMBEDDING_MODEL} downloaded."
echo ""

echo "Step 5: Verifying installed models..."
docker exec compliance-ollama ollama list
echo ""

echo "========================================"
echo "All models installed successfully!"
echo "========================================"