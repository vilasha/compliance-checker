package org.maria.compliance.model;

import lombok.Builder;

@Builder
public record ChunkResult(String text,
                          int chunkIndex,
                          String sectionHeading,
                          int startPosition,
                          int endPosition) {
}
