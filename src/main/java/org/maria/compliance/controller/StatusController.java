package org.maria.compliance.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.PolicyAnalysisReport;
import org.maria.compliance.model.TaskEvent;
import org.maria.compliance.model.TaskEventType;
import org.maria.compliance.model.UserUpload;
import org.maria.compliance.repository.UserUploadRepository;
import org.maria.compliance.service.TaskEventBus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@Slf4j
public class StatusController {

    private final TaskEventBus eventBus;
    private final UserUploadRepository uploadRepository;
    private final ObjectMapper objectMapper;
    private final long sseTimeoutMs;

    public StatusController(TaskEventBus eventBus,
                            UserUploadRepository uploadRepository,
                            ObjectMapper objectMapper,
                            @Value("${compliance.processing.sse-timeout:1h}") Duration sseTimeout) {
        this.eventBus = eventBus;
        this.uploadRepository = uploadRepository;
        this.objectMapper = objectMapper;
        this.sseTimeoutMs = sseTimeout.toMillis();
    }

    @GetMapping(value = "/status/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object stream(@PathVariable String taskId) {
        Optional<UserUpload> upload = uploadRepository.findByTaskId(taskId);
        if (upload.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);

        if (eventBus.hasHistory(taskId)) {
            eventBus.subscribe(taskId, emitter);
            return emitter;
        }

        // No in-memory history: task completed or failed before this subscription, and
        // memory was reclaimed (or this instance restarted). Synthesize a terminal event
        // from persisted state so the client still gets a result.
        emitFromPersistedState(emitter, upload.get());
        return emitter;
    }

    private void emitFromPersistedState(SseEmitter emitter, UserUpload upload) {
        TaskEventType type = switch (upload.status()) {
            case COMPLETED -> TaskEventType.COMPLETED;
            case FAILED, VALIDATION_ERROR -> TaskEventType.FAILED;
            default -> TaskEventType.UPLOADED;
        };

        TaskEvent.TaskEventBuilder builder = TaskEvent.builder()
                .taskId(upload.taskId())
                .type(type)
                .timestamp(Instant.now())
                .message("Restored from persisted state");

        if (upload.resultJson() != null) {
            if (type == TaskEventType.COMPLETED) {
                try {
                    PolicyAnalysisReport report = objectMapper.readValue(upload.resultJson(), PolicyAnalysisReport.class);
                    builder.report(report);
                } catch (JsonProcessingException e) {
                    log.warn("Could not parse persisted report for taskId={}: {}", upload.taskId(), e.getMessage());
                }
            } else if (type == TaskEventType.FAILED) {
                builder.errorMessage(upload.resultJson());
            }
        }

        try {
            emitter.send(SseEmitter.event().name(type.name()).data(builder.build()));
            emitter.complete();
        } catch (IOException e) {
            log.debug("Late-subscriber delivery failed: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }
}