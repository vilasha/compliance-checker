package org.maria.compliance.model;

import lombok.Builder;

import java.util.List;

@Builder
public record IngestionResult(String url,
                              String fileName,
                              int chunksCreated,
                              int embeddingsStored,
                              long processingTimeMs,
                              boolean success,
                              String errorMessage,
                              List<String> embeddingIds) {
}