package org.maria.compliance.model;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record DocumentSource(String url,
                             String fileName,
                             String documentType,
                             String language,
                             String lawName,
                             Integer year,
                             LocalDateTime downloadTimestamp) {
}