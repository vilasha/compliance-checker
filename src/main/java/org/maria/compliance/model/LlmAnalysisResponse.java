package org.maria.compliance.model;

import lombok.Builder;

/**
 * LLM response wrapper
 */
@Builder
public record LlmAnalysisResponse(String rawText,
                                  String modelUsed,
                                  int tokensUsed) {
}
