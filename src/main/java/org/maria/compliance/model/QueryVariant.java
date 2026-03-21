package org.maria.compliance.model;

import lombok.Builder;

/**
 * Represents a query variant for one analytical perspective
 */
@Builder
public record QueryVariant(String perspective,        // e.g., "strict_legal"
                           String query,              // The actual query text
                           String description,        // What this perspective looks for
                           double weight             // Importance multiplier
) {
}
