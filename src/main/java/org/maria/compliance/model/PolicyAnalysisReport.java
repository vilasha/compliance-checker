package org.maria.compliance.model;

import lombok.Builder;

import java.util.List;

/**
 * Final report for an uploaded policy PDF. Contains one {@link ComplianceAnalysisResult}
 * per detected section plus aggregate fields computed across all sections.
 */
@Builder
public record PolicyAnalysisReport(String taskId,
                                   String fileName,
                                   String language,
                                   List<ComplianceAnalysisResult> sectionResults,
                                   OverallRisk aggregateRisk,
                                   int totalViolations,
                                   long totalProcessingTimeMs) {
}