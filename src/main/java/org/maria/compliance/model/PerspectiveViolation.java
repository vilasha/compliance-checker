package org.maria.compliance.model;

import lombok.Builder;

/**
 * Violation identified from one perspective
 */
@Builder
public record PerspectiveViolation(Perspective perspective,
                                   Severity severity,
                                   String regulatoryText,      // Exact quoted text from regulation
                                   String violationDetail,     // What's wrong
                                   String source              // e.g., "Solvency II Article 132"
) {
}
