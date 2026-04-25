-- V2__drop_regulatory_chunks.sql
-- Vector storage for regulatory data is now managed by LangChain4j's PgVectorEmbeddingStore
-- which creates and owns its own table (regulatory_embeddings). See ADR-003.

-- Drop view first (depends on the table)
DROP VIEW IF EXISTS regulatory_metadata;

-- Drop trigger and its function (only used by regulatory_chunks)
DROP TRIGGER IF EXISTS update_regulatory_chunks_updated_at ON regulatory_chunks;
DROP FUNCTION IF EXISTS update_updated_at_column();

-- Drop the table (CASCADE would also work, but explicit is clearer in migrations)
DROP TABLE IF EXISTS regulatory_chunks;

-- NOTE: The pgvector extension stays - PgVectorEmbeddingStore needs it.