package org.maria.compliance.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;

/**
 * One event in the analysis lifecycle for a given task. Streamed over SSE and
 * also kept in {@code TaskEventBus} history for late subscribers to replay.
 * Null fields are omitted from JSON to keep the wire payload small.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskEvent(String taskId,
                        TaskEventType type,
                        Instant timestamp,
                        String message,
                        Integer sectionsTotal,
                        Integer sectionsCompleted,
                        ComplianceAnalysisResult sectionResult,
                        PolicyAnalysisReport report,
                        String errorMessage) {
}