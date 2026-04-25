package org.maria.compliance.model;

import lombok.Builder;

@Builder
public record RegulatoryMetadata(String lawName,
                                 Integer year,
                                 String language,
                                 Long chunkCount) {
}