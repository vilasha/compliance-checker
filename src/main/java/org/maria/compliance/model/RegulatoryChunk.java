package org.maria.compliance.model;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Represents a chunk of regulatory document stored in the vector database.
 * Each chunk contains a text excerpt and its vector embedding for similarity search.
 */
@Builder
public record RegulatoryChunk(Long id,
                              String lawName,
                              Integer year,
                              String language,
                              String sectionPath,
                              String originalText,
                              String sourceUrl,
                              float[] embedding,  // Vector embedding (1024 dimensions for bge-m3)
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt
) {
}