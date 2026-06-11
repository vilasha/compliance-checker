package org.maria.compliance.model;

/**
 * Declaration order is severity rank: a smaller ordinal means a more severe risk
 * UNKNOWN sits above LOW deliberately: "we could not analyze this" must never
 * aggregate as "this is fine" in a compliance report
 */
public enum OverallRisk {
    HIGH, MEDIUM, UNKNOWN, LOW
}