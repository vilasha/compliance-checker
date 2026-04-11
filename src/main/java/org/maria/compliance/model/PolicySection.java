package org.maria.compliance.model;

import lombok.Builder;

@Builder
public record PolicySection(int sectionNumber,
                            String heading,
                            String content,
                            int startPosition,
                            int endPosition) {
}
