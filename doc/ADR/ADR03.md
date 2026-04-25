# Architecture Decision Record: Data Access Layer Strategy

**ADR ID:** ADR-003  
**Status:** Accepted  
**Date:** 2026-04-25  
**Author:** Maria Ind  
**Supersedes:** Partial - overrides the data access approach implied in ADR-001 §3 (RAG Orchestration) and §5 (Data Curation Pipeline)

---

## Context

ADR-001 defined a `regulatory_chunks` table with explicit columns (`law_name`, `year`, `language`, `section_path`, `original_text`, `source_url`, `embedding vector(1024)`) and assumed all database interaction - including vector operations - would go through JdbcClient/JDBC templates.

During implementation of the repository layer, this approach revealed friction:

1. **PGVector type registration** - Using raw JDBC with `vector` columns requires registering the `PGvector` Java type on every database connection via `PGvector.addVectorType(connection)`. With HikariCP connection pooling, this means hooking into connection lifecycle, which is fragile and hard to test.

2. **Embedding serialization** - Converting `float[1024]` to/from PostgreSQL's vector string format (`[0.1,0.2,...,0.9]`) and handling the `::vector` cast in SQL is boilerplate that LangChain4j already solves.

3. **Search query complexity** - Cosine distance (`<=>`) queries, HNSW index tuning (`SET hnsw.ef_search`), metadata filtering, and score normalization (distance -> similarity) are all well-tested in LangChain4j's `PgVectorEmbeddingStore`.

4. **No Hibernate constraint** - The project avoids Hibernate (performance degradation at scale, per project coding preferences). JdbcClient is the preferred alternative, but it has no vector-aware features. LangChain4j's `PgVectorEmbeddingStore` uses its own JDBC connection management internally, sidestepping Hibernate entirely.

---

## Decision

Split the data access layer into two strategies based on data type:

### Vector data -> LangChain4j PgVectorEmbeddingStore

All embedding storage and vector similarity search goes through `EmbeddingStore<TextSegment>`, backed by `PgVectorEmbeddingStore`.

- **Table**: `regulatory_embeddings` (created and managed by LangChain4j)
- **Schema**: `embedding_id` (UUID), `embedding` (vector), `text` (text segment content), `metadata` (JSONB)
- **Regulatory metadata** (law_name, year, language, section_path, source_url) is stored in the JSONB `metadata` column
- **Metadata filtering** uses LangChain4j's `Filter` API (e.g., `metadataKey("language").isEqualTo("de")`) which translates to JSONB queries internally
- **Configuration**: Injected as a Spring bean via `EmbeddingStoreConfig`, sharing the existing `DataSource` (HikariCP pool with profile-based schema selection)

### Non-vector data -> JdbcClient

Audit logs, user uploads, and administrative metadata queries use Spring's `JdbcClient` with named parameters.

- **Tables**: `audit_log`, `user_uploads` (created by Flyway V1 migration)
- **Pattern**: Interface + `Jdbc*` implementation (e.g., `AuditLogRepository` / `JdbcAuditLogRepository`)
- **RowMappers**: Explicit lambda mappers, not `BeanPropertyRowMapper`, because records with custom types (`ProcessingStatus` enum, nullable `Integer`) need manual mapping
- **Enums**: Stored as strings via `.name()` on write, `valueOf()` on read - explicit, no `@Enumerated` surprises

### Cross-framework bridge: JdbcRegulatoryMetadataRepository

This is the deliberate hack in this architecture. `JdbcRegulatoryMetadataRepository` queries the `regulatory_embeddings` table - which is owned by LangChain4j - using JdbcClient. It reads the JSONB `metadata` column for aggregate queries that `EmbeddingStore` doesn't support:

- "How many chunks exist for FINMA 2024?" -> `COUNT(*)` with JSONB filters
- "List all ingested regulatory sources" -> `GROUP BY` over JSONB fields
- "Delete all chunks for a specific law/year before re-ingestion" -> `DELETE` with JSONB filters

This works because both `PgVectorEmbeddingStore` and `JdbcClient` share the same `DataSource` -> same HikariCP pool -> same PostgreSQL schema. The table name is injected from a single config property (`compliance.embedding-store.table`) into both the `EmbeddingStoreConfig` bean and the `JdbcRegulatoryMetadataRepository`, ensuring they always point at the same table.

**Why this is a hack**: `JdbcRegulatoryMetadataRepository` depends on LangChain4j's internal table schema (column names, JSONB structure). If LangChain4j changes its schema in a future version, this repository breaks silently. This coupling is accepted because:

1. The alternative (reimplementing vector storage in raw JDBC) is worse
2. The queries are simple aggregations, easy to fix if the schema changes
3. LangChain4j's PgVector table schema has been stable across major versions
4. The repository is isolated behind an interface - fixing the implementation doesn't ripple

---

## Table Ownership Summary

| Table | Owner | Access Method | Created By |
|---|---|---|---|
| `regulatory_embeddings` | LangChain4j | `EmbeddingStore` (primary) + `JdbcClient` (metadata queries) | `PgVectorEmbeddingStore` at startup |
| `audit_log` | Application | `JdbcClient` | Flyway V1 |
| `user_uploads` | Application | `JdbcClient` | Flyway V1 |
| `regulatory_chunks` | **Unused** | - | Flyway V1 (legacy, to be dropped in V2) |

---

## Tech Debt

1. **V1 migration leftovers** - The `regulatory_chunks` table, its HNSW index, and the `regulatory_metadata` view still exist from the V1 Flyway migration but are no longer used. A V2 migration should drop all three. Not urgent - empty tables have zero runtime cost.

2. **No timestamps on embeddings** - `PgVectorEmbeddingStore` doesn't create a `created_at` column, so we can't track when regulatory data was ingested. If needed, we can store an `ingested_at` field in the JSONB metadata, or add a column via V2 migration and trigger.

3. **JSONB query performance** - The `metadata->>'law_name'` queries in `JdbcRegulatoryMetadataRepository` are not indexed. For the expected data volume (thousands of regulatory chunks, not millions), sequential scan on JSONB is acceptable. If it becomes a bottleneck, add a GIN index: `CREATE INDEX idx_reg_metadata ON regulatory_embeddings USING gin (metadata)`.

---

## Consequences

### Benefits

- **No PGVector type registration headaches** - LangChain4j handles all vector serialization and connection-level setup internally
- **Tested vector search** - Similarity queries, HNSW index usage, and score normalization are battle-tested in the LangChain4j library, not hand-rolled
- **No Hibernate** - Both access strategies (EmbeddingStore and JdbcClient) operate without Hibernate, consistent with project constraints
- **Clean separation** - Vector concerns (embeddings, similarity) don't leak into the JdbcClient repositories
- **Single DataSource** - Both strategies share HikariCP, so connection pool limits, schema selection, and transaction management are unified

### Risks

- **Schema coupling** - `JdbcRegulatoryMetadataRepository` is coupled to LangChain4j's internal table schema
- **Two mental models** - Developers need to know which data goes through which access path
- **Testing gap** - `EmbeddingStore` is excluded from the test profile (`@Profile("!test")`), so integration tests for vector operations require Testcontainers with a real PostgreSQL+pgvector instance

---

## References

- [LangChain4j PgVectorEmbeddingStore](https://docs.langchain4j.dev/integrations/embedding-stores/pgvector)
- [Spring JdbcClient (Spring Framework 6.1+)](https://docs.spring.io/spring-framework/reference/data-access/jdbc/JdbcClient.html)
- [pgvector HNSW Index](https://github.com/pgvector/pgvector#hnsw)