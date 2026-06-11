# Compliance Checker

A RAG-based regulatory compliance checker for insurance and reinsurance policies. Upload a policy PDF and the system analyzes it section by section against FINMA, Solvency II, and Solvency UK regulations — fully locally, with no data leaving your machine.

Built and documented in public as part of the [**Ship It with Java** newsletter](https://www.linkedin.com/newsletters/ship-it-with-java-7454104954065313792/), where each major step of this project becomes an article on building production-grade AI systems with Java and Spring Boot.

## How it works

```
                 ┌────────────────────────────────────────────────────┐
                 │                   Spring Boot app                  │
                 │                                                    │
 Policy PDF ───▶ │  PDFBox text extraction → regex section detection  │
                 │       │                                            │
                 │       ▼                  ┌─────────────┐           │
                 │  per section: embed ───▶ │  pgvector   │ regulatory│
                 │  (bge-m3 via Ollama)     │  similarity │ embeddings│
                 │       │                  │  search     │ (122 docs)│
                 │       ▼                  └─────────────┘           │
                 │  compliance prompt → qwen2.5 (Ollama) → JSON       │
                 │       │                                            │
                 │       ▼                                            │
                 │  SSE progress stream → violations report in UI     │
                 └────────────────────────────────────────────────────┘
```

The regulatory knowledge base is filled by a built-in FINMA scraper (Sitecore search API, no headless browser — see ADR-004) plus a URL-ingestion endpoint restricted to an allowlist of official regulatory sources.

## Tech stack

Java 21, Spring Boot 4, LangChain4j (Ollama + pgvector modules), PostgreSQL 16 with pgvector, Flyway, Apache PDFBox, Caffeine, Thymeleaf + Bootstrap, plain `JdbcClient` (no Hibernate — see ADR-003). LLM and embeddings run locally on Ollama: `qwen2.5:3b` for analysis, `bge-m3` (1024-dim, multilingual) for embeddings.

## Quick start

Prerequisites: Docker with ~12 GB of memory available.

```bash
cp .env.example .env          # adjust passwords/ports if needed

# Infrastructure only (run the app from your IDE):
docker compose up -d

# Or everything, including the app:
docker compose --profile full up -d
```

The `ollama-pull` service downloads the models on first start (several GB — one-time). The app is then available at http://localhost:8080 (default dev credentials: `admin` / `admin123`, configured in `application.yml`).

To populate the knowledge base from FINMA:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,scrape
```

GPU setup, memory tuning, and troubleshooting: [doc/how-to/Docker-instructions.md](doc/how-to/Docker-instructions.md).

## Configuration

Everything lives under the `compliance.*` prefix in [`application.yml`](src/main/resources/application.yml) — models, context window, chunking, retrieval thresholds, section-detection regexes, scraper GUIDs, and processing limits. The file is heavily commented; the comments are the documentation.

## Design decisions

Architecture Decision Records live in [doc/ADR](doc/ADR):

| ADR | Decision |
|-----|----------|
| 001 | Overall RAG architecture for regulatory compliance |
| 002 | Multi-query retrieval with perspective synthesis (planned) |
| 003 | Data access: LangChain4j-owned vector table + JdbcClient, no ORM |
| 004 | Scraping FINMA via the Sitecore search API instead of a headless browser |
| 005 | Single-query RAG with JSON parse retries |
| 006/007 | Section-detection regex iterations (dates, inline references, Solvency II headings) |
| 008 | Async upload pipeline with SSE progress streaming |

## Roadmap

- [x] **1–3** Repository layer, embedding service, ingestion pipeline, FINMA scraper, Docker setup
- [x] **4** Single-query RAG end-to-end (retrieve → prompt → parse → report)
- [x] **5** Upload API with async processing and SSE progress streaming
- [ ] **6** Multi-query RAG: 5 analytical perspectives, parallel retrieval, deduplication, synthesis
- [ ] **7** Audit logging service (JSON file → Azure Blob)
- [ ] **8** Results UI: severity badges, perspective breakdown, citations
- [ ] **9** Integration tests (Testcontainers: PostgreSQL + pgvector, mocked Ollama)
- [ ] **10** Fine-tuning pipeline (LoRA on llama3.1:8b)
- [ ] **11** Terraform + CI/CD (Azure Container Apps, GitHub Actions)
- [ ] **12** Observability (OpenTelemetry, Grafana dashboards)

## Disclaimer

This is an engineering showcase, not legal advice. Analysis quality depends on the ingested corpus and the local model; findings always require review by a qualified compliance professional.

## License

See [LICENSE](LICENSE).