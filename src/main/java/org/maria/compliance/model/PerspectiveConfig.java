package org.maria.compliance.model;

import lombok.Builder;

/**
 * Configuration for one analytical perspective
 */
@Builder
public record PerspectiveConfig(String name, String description, double weight) {
}