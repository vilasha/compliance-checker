package org.maria.compliance.model;

public enum TaskEventType {

    /**
     * Task accepted; file persisted; async processing queued
     */
    UPLOADED,

    /**
     * Text extraction from PDF in progress
     */
    EXTRACTING,

    /**
     * Section detection complete; section count known
     */
    SECTIONS_DETECTED,

    /**
     * A section's analysis is about to start; carries section index and heading
     */
    SECTION_STARTED,

    /**
     * One section finished analysis; includes per-section result and progress
     */
    SECTION_ANALYZED,

    /**
     * All sections done; final aggregated report attached
     */
    COMPLETED,

    /**
     * Processing failed; error message attached
     */
    FAILED
}