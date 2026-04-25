package org.maria.compliance.model;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record UserUpload(Long id,
                         String taskId,
                         String username,
                         String fileName,
                         Long fileSizeBytes,
                         LocalDateTime uploadTimestamp,
                         ProcessingStatus status,
                         String resultJson) {
}