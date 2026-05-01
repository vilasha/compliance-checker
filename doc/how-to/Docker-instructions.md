# Docker Setup Guide

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) 4.x+ (includes Docker Compose v2)
- At least **16 GB RAM** (Ollama with llama3.1:8b needs ~8 GB alone)
- ~10 GB free disk space for Docker images and model weights

## Quick Start

```bash
# 1. Start infrastructure (PostgreSQL + Ollama)
docker compose up -d

# 2. Wait for model downloads (automatic via ollama-pull service)
docker compose logs -f ollama-pull

# 3. Verify everything is ready
./infra/verify-setup.sh        # Linux/macOS
infra\verify-setup.bat         # Windows
```

The `ollama-pull` service runs automatically on startup: it waits for Ollama to be healthy, pulls the required models, and exits. On first run this takes 10-30 minutes depending on your internet speed. Subsequent starts are instant because models are cached in a Docker volume.

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│                 Host Machine                     │
│                                                  │
│  ┌──────────────────┐  ┌──────────────────────┐  │
│  │ compliance-       │  │ compliance-ollama    │  │
│  │ postgres          │  │                      │  │
│  │ pgvector/         │  │ ollama/ollama:0.22.0 │  │
│  │ pgvector:pg16     │  │ - llama3.1:8b        │  │
│  │ Port 5432         │  │ - bge-m3             │  │
│  │ Vol: postgres_data│  │ Port 11434           │  │
│  │ Mem: 1 GB         │  │ Vol: ollama_data     │  │
│  └──────────────────┘  │ Mem: 10 GB           │  │
│           │             └──────────────────────┘  │
│           │                        │              │
│           └────── compliance-network ─────┘       │
│                        │                          │
│           ┌────────────┴───────────┐              │
│           │ Spring Boot App        │              │
│           │ (IDE or --profile full)│              │
│           └────────────────────────┘              │
└──────────────────────────────────────────────────┘
```

---

## Services

### postgres (always runs)
PostgreSQL 16 with the pgvector extension for vector similarity search.

| Setting    | Value |
|------------|-------|
| Image      | `pgvector/pgvector:pg16` |
| Port       | `5432` (configurable via `.env`) |
| Database   | `compliance_db` |
| Credentials| Set in `.env` file |
| Memory cap | 1 GB |

### ollama (always runs)
Ollama LLM server for embeddings (bge-m3) and chat inference (llama3.1:8b).

| Setting    | Value |
|------------|-------|
| Image      | `ollama/ollama:0.22.0` (pinned) |
| Port       | `11434` (configurable via `.env`) |
| Memory cap | 10 GB (adjustable — see Resource Tuning) |

### ollama-pull (runs once, then exits)
Init service that automatically pulls models when the Ollama server becomes healthy. Idempotent — re-running skips already-downloaded models.

### app (opt-in via `--profile full`)
The Spring Boot application itself, containerized. For local development, run from your IDE instead.

```bash
# Full stack (all containers including the app)
docker compose --profile full up -d

# Dev workflow (only infrastructure, app runs in IDE)
docker compose up -d
```

---

## Configuration (.env)

All Docker settings live in the `.env` file at the project root. Copy the provided `.env` and adjust:

```bash
# PostgreSQL
POSTGRES_DB=compliance_db
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres         # change in production
POSTGRES_PORT=5432

# Ollama
OLLAMA_PORT=11434
OLLAMA_CHAT_MODEL=llama3.1:8b
OLLAMA_EMBEDDING_MODEL=bge-m3

# Resource limits
OLLAMA_MEMORY_LIMIT=10g           # see Resource Tuning below
POSTGRES_MEMORY_LIMIT=1g
```

> **Security note**: `.env` is in `.gitignore`. Never commit credentials to version control. For production, use a secrets manager (e.g. Azure Key Vault).

---

## Manual Model Setup (alternative to ollama-pull)

If you prefer to pull models manually instead of using the init service:

**Linux / macOS:**
```bash
./infra/setup-models.sh
```

**Windows:**
```cmd
infra\setup-ollama-models.bat
```

**Or directly via Docker:**
```bash
docker exec compliance-ollama ollama pull llama3.1:8b
docker exec compliance-ollama ollama pull bge-m3
```

Model sizes:
- `llama3.1:8b`: ~4.7 GB
- `bge-m3`: ~2.4 GB

Verify installed models:
```bash
docker exec compliance-ollama ollama list
```

---

## GPU Acceleration (optional)

If you have an NVIDIA GPU with the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html) installed, add GPU access to the Ollama service.

Create a `docker-compose.override.yml`:

```yaml
services:
  ollama:
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
```

This file is automatically merged with `docker-compose.yml` by Docker Compose. Inference speed improves roughly 10-20x with a modern GPU.

> **macOS note**: Docker Desktop on macOS does not support GPU passthrough. Run Ollama natively (outside Docker) for GPU acceleration on Apple Silicon.

---

## Resource Tuning

### Memory requirements by model

| Component | RAM Usage | Recommendation |
|-----------|-----------|----------------|
| llama3.1:8b (inference) | ~6 GB | Minimum for chat |
| bge-m3 (embeddings) | ~1.5 GB | Loaded on demand |
| Ollama overhead | ~0.5 GB | Server process |
| PostgreSQL + pgvector | ~200-500 MB | Depends on dataset size |
| Spring Boot app | ~300-500 MB | With active processing |

### Tuning for 16 GB machines

If you have exactly 16 GB RAM, Ollama + Postgres + your IDE will be tight. Options:

1. **Reduce Ollama memory limit** to `8g` in `.env` — Ollama will swap models in/out of RAM rather than keeping both loaded
2. **Use a smaller chat model** — set `OLLAMA_CHAT_MODEL=llama3.2:3b` in `.env` (trades accuracy for ~3 GB less RAM)
3. **Run Ollama natively** instead of in Docker to avoid Docker Desktop's memory overhead

---

## Useful Commands

```bash
# View real-time logs
docker compose logs -f ollama
docker compose logs -f postgres

# Stop everything (data preserved in volumes)
docker compose down

# Stop and delete all data (clean slate)
docker compose down -v

# Rebuild the app image after code changes
docker compose --profile full build app

# Shell into PostgreSQL
docker exec -it compliance-postgres psql -U postgres -d compliance_db

# Test Ollama API
curl http://localhost:11434/api/tags
```

---

## Troubleshooting

### Port already in use
```
Error: bind: address already in use
```
Another process is using port 5432 or 11434. Either stop the conflicting process or change the port in `.env`:
```
POSTGRES_PORT=5433
OLLAMA_PORT=11435
```

### Ollama container keeps restarting
Check if it's hitting the memory limit:
```bash
docker inspect compliance-ollama --format '{{.State.OOMKilled}}'
```
If `true`, increase `OLLAMA_MEMORY_LIMIT` in `.env`.

### Models not found after restart
Models are stored in the `ollama_data` Docker volume. If you ran `docker compose down -v`, the volume was deleted. Re-run `docker compose up -d` to trigger the `ollama-pull` service again.

### Slow model downloads
Model downloads go through Ollama's CDN. If downloads are slow:
- Check your internet connection
- Try pulling one model at a time via `docker exec`
- Ollama resumes interrupted downloads automatically