package org.maria.compliance.model;

import lombok.Builder;

import java.util.List;

/**
 * Final structured result with perspective breakdown
 */
@Builder
public record ComplianceAnalysisResult(String policySection,
                                       List<PerspectiveViolation> violations,  // Grouped by perspective
                                       OverallRisk overallRisk,
                                       String recommendation,
                                       long processingTimeMs) {
}
