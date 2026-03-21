package org.maria.compliance.model;

import lombok.Builder;

import java.util.List;

/**
 * Regulatory chunk with relevance score
 */
@Builder
public record ScoredRegulatoryChunk(RegulatoryChunk chunk,
                                    double relevanceScore,     // Combined score from frequency + similarity + weight
                                    List<String> foundByPerspectives   // Which perspectives retrieved this
) {
}
