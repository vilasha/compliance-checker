package org.maria.compliance.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Entry point for the upload-and-analyze flow. Accepts a policy PDF, persists
 * the upload record, kicks off async processing, and returns a taskId the
 * client uses to subscribe to lifecycle events via event bus
 */
public interface AnalysisOrchestrator {

    /**
     * Validate, persist, and queue the upload for analysis.
     *
     * @return the taskId to feed into {@code GET /api/status/{taskId}}
     * @throws IllegalArgumentException for empty, oversized, or non-PDF files
     */
    String submit(MultipartFile file, String language, String username);
}