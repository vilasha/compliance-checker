package org.maria.compliance.model;

/**
 * Declaration order is severity rank: a smaller ordinal means a more severe finding
 * UNKNOWN is used when the LLM returned a severity outside the expected vocabulary
 * failing toward "needs review" rather than silently downgrading to LOW
 */
public enum Severity {
    HIGH, MEDIUM, UNKNOWN, LOW
}