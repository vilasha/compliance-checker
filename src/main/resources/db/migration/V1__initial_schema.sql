-- V1__initial_schema.sql
-- Initial database schema for Regulatory Compliance Checker

-- Enable PGVector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Regulatory chunks table with vector embeddings
CREATE TABLE regulatory_chunks (
    id BIGSERIAL PRIMARY KEY,
    law_name VARCHAR(255) NOT NULL,
    year INTEGER NOT NULL,
    language VARCHAR(10) NOT NULL,
    section_path TEXT,
    original_text TEXT NOT NULL,
    source_url TEXT,
    embedding vector(1024),  -- bge-m3 produces 1024-dimensional vectors
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for faster queries
CREATE INDEX idx_regulatory_law_name ON regulatory_chunks(law_name);
CREATE INDEX idx_regulatory_language ON regulatory_chunks(language);
CREATE INDEX idx_regulatory_year ON regulatory_chunks(year);

-- Vector similarity index using HNSW (Hierarchical Navigable Small World)
-- This is the most efficient index for vector similarity search
CREATE INDEX idx_regulatory_embedding_hnsw ON regulatory_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- Alternative: IVFFlat index (comment out HNSW above and uncomment this if needed)
-- CREATE INDEX idx_regulatory_embedding_ivfflat ON regulatory_chunks
-- USING ivfflat (embedding vector_cosine_ops)
-- WITH (lists = 100);

-- Audit log table
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    username VARCHAR(255) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    processing_status VARCHAR(50) NOT NULL,  -- SUCCESS, FAILURE, VALIDATION_ERROR
    violation_count INTEGER,
    error_message TEXT,
    processing_time_ms BIGINT,
    session_id VARCHAR(100),
    ip_address VARCHAR(50)
);

-- Indexes for audit log queries
CREATE INDEX idx_audit_timestamp ON audit_log(timestamp DESC);
CREATE INDEX idx_audit_username ON audit_log(username);
CREATE INDEX idx_audit_status ON audit_log(processing_status);

-- User uploads tracking table (optional, for persistence)
CREATE TABLE user_uploads (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(100) NOT NULL UNIQUE,
    username VARCHAR(255) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    upload_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50) NOT NULL,  -- UPLOADING, PROCESSING, COMPLETED, FAILED
    result_json TEXT
);

CREATE INDEX idx_uploads_task_id ON user_uploads(task_id);
CREATE INDEX idx_uploads_username ON user_uploads(username);
CREATE INDEX idx_uploads_timestamp ON user_uploads(upload_timestamp DESC);

-- Function to automatically update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to update updated_at on regulatory_chunks
CREATE TRIGGER update_regulatory_chunks_updated_at
    BEFORE UPDATE ON regulatory_chunks
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- View for regulatory metadata (useful for caching)
CREATE VIEW regulatory_metadata AS
SELECT DISTINCT
    law_name,
    year,
    language,
    COUNT(*) as chunk_count,
    MIN(created_at) as first_created,
    MAX(updated_at) as last_updated
FROM regulatory_chunks
GROUP BY law_name, year, language
ORDER BY law_name, year DESC;