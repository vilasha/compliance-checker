package org.maria.compliance.model;

import lombok.Builder;

/**
 * Request body for {@code POST /api/analyze}.
 */
@Builder
public record AnalyzeRequest(String text, String language) {
}