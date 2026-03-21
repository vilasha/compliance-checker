package org.maria.compliance.model;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Represents an audit log entry for compliance checking requests.
 * Tracks who uploaded what, when, and the outcome.
 */
@Builder
public record AuditLog(Long id,
                       LocalDateTime timestamp,
                       String username,
                       String fileName,
                       Long fileSizeBytes,
                       ProcessingStatus processingStatus,
                       Integer violationCount,
                       String errorMessage,
                       Long processingTimeMs,
                       String sessionId,
                       String ipAddress) {

}