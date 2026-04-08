# Architecture Decision Record: RAG-based Regulatory Compliance Checker for Insurance/Reinsurance

**ADR ID:** ADR-001  
**Status:** Proposed  
**Date:** 2025-03-30  
**Author:** Maria Ind  
**Reviewers:** [Stakeholders]

---

## Context

### Business Need
Insurance and reinsurance companies must ensure their policy documents comply with evolving regulatory requirements such as **Solvency II**, **FINMA** (Swiss), and **Solvency UK**. Manual compliance checking is error‑prone and time‑consuming. We need an automated system that:
- Allows users to upload policy PDFs.
- Compares policy content against a curated set of regulatory documents.
- Identifies potential violations and displays the exact regulatory text that is violated.
- Provides an audit trail of all uploads and analyses.

### Key Requirements
1. **Regulatory Data Curation**  
   - Ingest PDFs from FINMA, Solvency II, Solvency UK.  
   - Store with metadata: law name, year, language (German/Italian/French/English).  
   - Chunk by **section** (detect headings/articles).  
   - Embed chunks using a multilingual model and store in **PGVector**.

2. **User Upload & Analysis**  
   - Web UI (Thymeleaf) with Azure AD authentication.  
   - Accept PDFs ≤10 MB, reject larger.  
   - Parse PDF to text (no OCR), split into sections/paragraphs.  
   - For each chunk, retrieve similar regulatory chunks via vector search.  
   - Use an LLM to identify violations and quote exact regulatory text.  
   - Show progress via **Server‑Sent Events (SSE)** and display results in a pop‑up.

3. **LLM Strategy**  
   - Base model: **`llama3.2:1b`** (lightweight, multilingual).  
   - Fine‑tune on the **`rcds/swiss_rulings`** dataset to improve legal reasoning in German/French/Italian.  
   - Serve via **Ollama** on a low‑cost Azure VM.

4. **Non‑Functional Requirements**  
   - **Security**: Azure AD authentication; secrets in Azure Key Vault (cloud) or local `application-local.yml` (excluded from git).  
   - **Audit Logging**: All requests and file uploads logged – locally to disk, in Azure to Blob Storage (cold tier).  
   - **Caching**: Use Caffeine for frequently accessed metadata.  
   - **Monitoring**: OpenTelemetry + Grafana (Prometheus/Loki) on the VM.  
   - **Concurrency**: Support up to 5 simultaneous users.  
   - **Deployment**: Local (Docker Compose) and Azure (Terraform).  
   - **CI/CD**: Azure DevOps pipelines for build, test, and deployment.

---

## Decision

We will build a **Retrieval-Augmented Generation (RAG)** system using **Java 21/Spring Boot**, **LangChain4j**, **PGVector**, and a fine‑tuned **Llama 3.2 1B** model. The architecture is split into two main flows: **data curation** (one‑time / periodic) and **user upload & analysis** (interactive).

### High‑Level Component Diagram

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   User Browser  │────▶│  Spring Boot    │────▶│   PostgreSQL    │
│  (Thymeleaf UI) │◀────│    (Backend)    │◀───▶│   + PGVector    │
└─────────────────┘  SSE│                 │     └─────────────────┘
                        │                 │────▶┌─────────────────┐
                        │                 │     │   Ollama        │
                        │                 │────▶│ (fine‑tuned     │
                        └─────────────────┘     │  llama3.2:1b    │
                              │   │             │  & bge-m3)       │
                              │   └──────────────────┐            │
                              ▼                      ▼            │
                    ┌─────────────────┐    ┌─────────────────┐    │
                    │ Azure Blob      │    │ Azure Key Vault │    │
                    │ (Audit logs)    │    │   (Secrets)     │    │
                    └─────────────────┘    └─────────────────┘    │
                                                              └────┘
```

### Detailed Component Responsibilities

#### 1. **Web UI (Thymeleaf + Bootstrap)**
   - Login via Azure AD (Spring Security OAuth2).
   - Upload form with file size validation.
   - SSE endpoint to receive real‑time progress updates.
   - Modal pop‑up to display violations with exact regulatory text.

#### 2. **PDF Processing & Chunking**
   - **Apache PDFBox** for text extraction (supports European languages).
   - **Section detection**: Use heuristics (font size, bold, patterns like "Art.", "§") to split the document into logical sections. Fallback to paragraph splitting (`\n\n`).
   - Chunks are stored temporarily in memory during analysis.

#### 3. **RAG Orchestration (LangChain4j)**
   - **Embedding Model**: **`bge-m3`** served by Ollama. This multilingual model supports German, French, Italian, and English, making it ideal for both regulatory documents and policy uploads. LangChain4j’s Ollama integration will be used to call the embedding endpoint.
   - **Vector Store**: PGVector with cosine similarity search.
   - **Retrieval**: For each policy chunk, fetch top‑k (e.g., k=5) regulatory chunks.
   - **Prompt Construction**: Combine policy chunk + retrieved regulatory texts. Prompt engineered for violation detection and exact quoting.
   - **LLM Call**: Send to Ollama’s fine‑tuned `llama3.2:1b` model.

#### 4. **LLM Serving (Ollama)**
   - Base model: `llama3.2:1b`.
   - Fine‑tuned using **LoRA** on the `rcds/swiss_rulings` dataset to enhance legal reasoning in German, French, and Italian.
   - Custom Ollama `Modelfile` that imports the base model and fine‑tuned adapter weights.
   - Embedding model `bge-m3` runs in the same Ollama instance.
   - Runs on a **Standard_B1s** Azure VM (1 vCPU, 1 GB RAM) – cheapest tier, sufficient for 1B model inference and embedding at low concurrency.

#### 5. **Data Curation Pipeline**
   - Separate batch job (Spring Boot command line runner or scheduled task).
   - Reads regulatory PDFs from a configured folder/URL.
   - Applies same PDF parsing and section chunking.
   - Generates embeddings via Ollama’s `bge-m3` and stores in PGVector with metadata:
     - `law_name`, `year`, `language`, `section_path`, `original_text`, `source_url`.
   - Updates are incremental (new versions of regulations) – can be triggered manually or via CI/CD.

#### 6. **Audit Logging**
   - **Logback** custom appender that checks a property `audit.destination`.
   - **Local**: writes to `./logs/audit/` as JSON lines.
   - **Azure**: uses Azure Storage SDK to write to a Blob container (cold tier) with a filename pattern `yyyy/MM/dd/HH/audit-<timestamp>.log`.
   - Logged fields: timestamp, authenticated user, file name, file size, processing outcome (success/failure), violation count.

#### 7. **Caching**
   - Spring Cache abstraction with **Caffeine**.
   - Cache regulatory document metadata (law names, years) to reduce DB queries.
   - Cache embedding results? Not recommended due to dynamic nature of queries; instead rely on fast vector search.

#### 8. **Monitoring & Observability**
   - **OpenTelemetry Java agent** auto‑instruments Spring Boot, database, and HTTP calls.
   - Metrics and traces exported to a local Prometheus instance on the VM (or Azure Monitor if budget allows).
   - **Grafana** dashboards for:
     - Request rate / latency / error rate.
     - Vector search performance.
     - LLM response time and token usage.
     - VM resource usage (CPU, memory).
   - Logs aggregated via **Loki** (on VM) and displayed in Grafana.

#### 9. **Authentication & Secrets**
   - **Azure AD** integration via Spring Security OAuth2. Users must be members of a specific Azure AD group (e.g., "Compliance Officers").
   - **Secrets**:
     - **Local**: `application-local.yml` (excluded from git) contains database password, Azure AD client secret, etc.
     - **Azure**: All secrets stored in **Azure Key Vault**. VM accesses Key Vault via Managed Identity. Spring Boot uses `azure-spring-boot-starter-keyvault-secrets` to inject properties at runtime.
   - **Terraform** creates Key Vault and stores generated secrets (e.g., PostgreSQL admin password) automatically. Azure AD client secret is set manually or via pipeline secret.

#### 10. **Infrastructure as Code (Terraform)**
   - **Provider**: AzureRM, region `germanywestcentral`.
   - **Resources**:
     - Resource group.
     - PostgreSQL Flexible Server (B1ms) with PGVector enabled.
     - VM (Standard_B1s) with public IP, NSG (port 22, 8080, 11434).
     - Storage account (cold tier) for audit logs.
     - Key Vault with access policies for VM managed identity.
     - Optional: Application Insights for basic monitoring.
   - **State** stored in an Azure Storage Account backend (configured manually or via pipeline).

#### 11. **CI/CD (Azure DevOps)**
   - Pipeline YAML with stages:
     1. **Build**: Compile, run unit tests (Java 21).
     2. **Integration Test**: Use Testcontainers (PostgreSQL + Ollama mock) to test RAG flow.
     3. **Package**: Create Spring Boot fat JAR.
     4. **Terraform Plan/Apply**: Provision/update infrastructure (using service principal with Contributor rights).
     5. **Deploy**: Copy JAR to VM via SSH, restart systemd service.
   - Secrets (Azure AD client secret, Terraform backend key) stored in Azure DevOps Library variable groups.

#### 12. **Local Development Environment**
   - Docker Compose with:
     - `ankane/pgvector` (PostgreSQL + PGVector)
     - `ollama/ollama` (pre‑pull `llama3.2:1b`, `bge-m3`, and fine‑tuned model)
   - Spring Boot with profile `local` reads `application-local.yml`.
   - Audit logs written to `./logs/audit`.
   - All services accessible on `localhost`.

### Fine‑Tuning Strategy for Llama 3.2 1B

- **Dataset**: `rcds/swiss_rulings` (Hugging Face). Focus on German subset, but include French/Italian for multilingual capability.
- **Task**: Supervised fine‑tuning (instruction style) using **LoRA** (Low‑Rank Adaptation) for efficiency.
- **Training Data Format**: Convert rulings into question‑answer or instruction‑response pairs, e.g.:
  - *Instruction*: "Based on Swiss law, what is the consequence of a debtor proving partial payment?"  
    *Response*: "If the debtor proves partial payment with documents, the definitive opposition is rejected only for the extinguished part (DTF 124 III 501 consid. 3b)."
- **Infrastructure**:
  - **Local prototyping**: GPU workstation or cloud notebook (Azure ML with low‑priority GPU).
  - **Production retraining**: Scheduled Azure ML job using serverless Spark with a spot GPU VM (e.g., NCas T4 v3). Artifacts stored in Blob.
- **Integration with Ollama**:
  - Export LoRA adapter weights.
  - Create a `Modelfile`:
    ```
    FROM llama3.2:1b
    ADAPTER /path/to/lora-adapter.gguf
    TEMPLATE "{{ .Prompt }}"
    ```
  - Build and run the custom model: `ollama create swiss-legal -f Modelfile`

---

## Consequences

### Benefits
- **Accuracy**: Fine‑tuning on Swiss legal corpus yields better understanding of domain‑specific language and reasoning.
- **Cost‑effective**: Small model on cheapest VM keeps operational costs low.
- **Multilingual Embeddings**: `bge-m3` via Ollama provides high‑quality multilingual vector representations, essential for German/French/Italian regulatory documents.
- **Auditability**: Exact regulatory text quoted, not summarized, ensuring compliance.
- **Scalability**: Up to 5 concurrent users supported without complex infrastructure.
- **Security**: Azure AD and Key Vault ensure proper identity and secret management.
- **Reproducibility**: Terraform + CI/CD enable consistent deployments.
- **Java 21**: Leverages latest LTS features (virtual threads, improved performance) for concurrent request handling.

### Trade‑offs & Risks
- **Model performance**: A 1B parameter model may still struggle with very complex legal reasoning. We mitigate by fine‑tuning and using retrieval to provide context.
- **Section detection heuristics**: May fail on poorly formatted PDFs. Fallback to paragraph splitting ensures at least some structure.
- **Language coverage**: Fine‑tuning on Swiss rulings primarily improves German; French/Italian may lag. We will monitor and consider additional data if needed.
- **Cold start**: First request after deployment may be slow due to model loading into memory. Keep Ollama running and pre‑loaded.
- **Maintenance**: Fine‑tuning requires periodic retraining as new rulings are added. Automate via CI/CD.
- **Ollama embedding latency**: Using a separate model for embeddings adds network hop, but on local VM it’s acceptable. Could consider in‑process model later if latency becomes an issue.

### Open Issues
- **Exact quoting**: Need to ensure LLM reliably outputs the exact regulatory text from the retrieved chunk. Prompt engineering and post‑processing (string matching) may be required.
- **SSE implementation**: Client reconnection handling and timeout management need careful design.
- **Azure AD group synchronization**: Ensure user group is properly maintained.

---

## Technical Decisions Summary

| Area                | Decision                                                                 |
|---------------------|--------------------------------------------------------------------------|
| Language            | Java 21, Spring Boot 3                                                   |
| Frontend            | Thymeleaf + Bootstrap + SSE                                              |
| PDF parsing         | Apache PDFBox with custom section detection                              |
| RAG framework       | LangChain4j                                                              |
| Vector DB           | PostgreSQL + PGVector                                                    |
| Embedding model     | **`bge-m3`** served by Ollama (multilingual)                             |
| LLM                 | llama3.2:1b fine‑tuned on rcds/swiss_rulings, served by Ollama           |
| Authentication      | Azure AD (OAuth2)                                                        |
| Secrets             | Azure Key Vault (cloud) / local YML (local)                              |
| Audit logging       | Logback custom appender → local file or Azure Blob cold tier             |
| Caching             | Caffeine (Spring Cache)                                                  |
| Monitoring          | OpenTelemetry + Grafana (Prometheus, Loki) on VM                         |
| Deployment (local)  | Docker Compose                                                           |
| Deployment (Azure)  | Terraform (VM, PostgreSQL, Storage, Key Vault)                           |
| CI/CD               | Azure DevOps (build, test, terraform, deploy)                            |
| Fine‑tuning infra   | Azure ML serverless with spot GPU (or local GPU for prototyping)         |

---

## Appendix: Example Data Flow

1. **User logs in** via Azure AD, redirected back to `/`.
2. **Uploads** `policy.pdf` (≤10 MB). Backend validates and returns a `taskId`.
3. Browser opens SSE connection to `/api/status/{taskId}`.
4. Backend processes asynchronously:
   - Parse PDF → text → sections.
   - For each section, generate embedding via Ollama `bge-m3` → query PGVector → retrieve top 5 regulatory chunks.
   - Aggregate chunks, build prompt, call fine‑tuned LLM via Ollama.
   - Parse LLM response into structured violations.
5. During processing, backend sends SSE events: `PARSING`, `CHUNKING`, `RETRIEVING`, `ANALYZING`, `DONE`.
6. Final event contains JSON list of violations: each with `policySnippet` and `regulatoryText`.
7. UI shows pop‑up with violations.
8. Audit log entry written to configured destination.

---

## References
- [LangChain4j Documentation](https://docs.langchain4j.dev/)
- [PGVector](https://github.com/pgvector/pgvector)
- [Ollama](https://ollama.com/)
- [Ollama Models (bge-m3)](https://ollama.com/library/bge-m3)
- [rcds/swiss_rulings Dataset](https://huggingface.co/datasets/rcds/swiss_rulings)
- [Azure AD Spring Boot Starter](https://github.com/Azure/azure-sdk-for-java/tree/main/sdk/spring/azure-spring-boot-starter-active-directory)
- [Azure Key Vault Spring Boot Starter](https://github.com/Azure/azure-sdk-for-java/tree/main/sdk/spring/azure-spring-boot-starter-keyvault-secrets)