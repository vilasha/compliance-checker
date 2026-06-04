package org.maria.compliance.service;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.ProcessingStatus;
import org.maria.compliance.model.TaskEvent;
import org.maria.compliance.model.TaskEventType;
import org.maria.compliance.model.UserUpload;
import org.maria.compliance.repository.UserUploadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class AnalysisOrchestratorImpl implements AnalysisOrchestrator {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("application/pdf");

    private final AsyncAnalysisWorker worker;
    private final TaskEventBus eventBus;
    private final UserUploadRepository uploadRepository;
    private final long maxFileSizeBytes;

    public AnalysisOrchestratorImpl(AsyncAnalysisWorker worker,
                                    TaskEventBus eventBus,
                                    UserUploadRepository uploadRepository,
                                    @Value("${compliance.pdf.max-file-size-mb:10}") int maxFileSizeMb) {
        this.worker = worker;
        this.eventBus = eventBus;
        this.uploadRepository = uploadRepository;
        this.maxFileSizeBytes = maxFileSizeMb * 1024L * 1024L;
    }

    @Override
    public String submit(MultipartFile file, String language, String username) {
        validate(file);

        String taskId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf";

        uploadRepository.save(UserUpload.builder()
                .taskId(taskId)
                .username(username)
                .fileName(fileName)
                .fileSizeBytes(file.getSize())
                .status(ProcessingStatus.UPLOADING)
                .build());

        eventBus.publish(TaskEvent.builder()
                .taskId(taskId)
                .type(TaskEventType.UPLOADED)
                .timestamp(Instant.now())
                .message("Upload received: " + fileName)
                .build());

        // Read bytes before returning so the MultipartFile handle isn't consumed
        // when the async thread runs — request scope may already be closed by then.
        byte[] pdfBytes;
        try {
            pdfBytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded file", e);
        }

        // Cross-bean call goes through the AOP proxy, so @Async on worker.run dispatches
        // to the taskExecutor as intended. A this.run(...) call from the same class
        // would bypass the proxy and run synchronously — Step 5's original bug.
        worker.run(taskId, pdfBytes, fileName, language);
        return taskId;
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new IllegalArgumentException("File exceeds max size of " + maxFileSizeBytes + " bytes");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only PDF uploads are accepted (got: " + contentType + ")");
        }
    }
}