package org.maria.compliance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.*;
import org.maria.compliance.repository.UserUploadRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The async worker for the upload pipeline. Lives in its own bean so the
 * {@code @Async} dispatch happens through Spring's AOP proxy — calling an
 * {@code @Async} method from the same class via {@code this.foo()} would
 * bypass the proxy and run synchronously. Cross-bean invocation is the
 * idiomatic fix (alternatives: AopContext.currentProxy(), self-injection;
 * both work but obscure the intent).
 */
@Service
@Slf4j
public class AsyncAnalysisWorker {

    private final PdfProcessingService pdfProcessingService;
    private final SingleQueryRagService ragService;
    private final TaskEventBus eventBus;
    private final UserUploadRepository uploadRepository;
    private final ObjectMapper objectMapper;

    public AsyncAnalysisWorker(PdfProcessingService pdfProcessingService,
                               SingleQueryRagService ragService,
                               TaskEventBus eventBus,
                               UserUploadRepository uploadRepository,
                               ObjectMapper objectMapper) {
        this.pdfProcessingService = pdfProcessingService;
        this.ragService = ragService;
        this.eventBus = eventBus;
        this.uploadRepository = uploadRepository;
        this.objectMapper = objectMapper;
    }

    @Async("taskExecutor")
    public void run(String taskId, byte[] pdfBytes, String fileName, String language) {
        long startTime = System.currentTimeMillis();
        try {
            uploadRepository.updateStatus(taskId, ProcessingStatus.PROCESSING);

            eventBus.publish(TaskEvent.builder()
                    .taskId(taskId)
                    .type(TaskEventType.EXTRACTING)
                    .timestamp(Instant.now())
                    .message("Extracting text from PDF")
                    .build());

            String text = pdfProcessingService.extractText(pdfBytes, fileName);
            List<PolicySection> sections = pdfProcessingService.detectSections(text);
            int total = sections.size();

            eventBus.publish(TaskEvent.builder()
                    .taskId(taskId)
                    .type(TaskEventType.SECTIONS_DETECTED)
                    .timestamp(Instant.now())
                    .message("Detected " + total + " section(s)")
                    .sectionsTotal(total)
                    .build());

            // Sequential analysis. Parallelizing would require an Ollama instance
            // capable of concurrent requests and is a future optimization (ADR-008).
            // SECTION_STARTED fires before the LLM call so the UI shows progress even
            // when a single section takes minutes on slow hardware — without it the
            // stream looks frozen between SECTIONS_DETECTED and the first result.
            List<ComplianceAnalysisResult> results = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                PolicySection section = sections.get(i);

                eventBus.publish(TaskEvent.builder()
                        .taskId(taskId)
                        .type(TaskEventType.SECTION_STARTED)
                        .timestamp(Instant.now())
                        .message("Analyzing: " + section.heading())
                        .sectionsTotal(total)
                        .sectionsCompleted(i)
                        .build());

                ComplianceAnalysisResult sectionResult = ragService.analyze(section.content(), language);
                results.add(sectionResult);

                eventBus.publish(TaskEvent.builder()
                        .taskId(taskId)
                        .type(TaskEventType.SECTION_ANALYZED)
                        .timestamp(Instant.now())
                        .message("Analyzed: " + section.heading())
                        .sectionsTotal(total)
                        .sectionsCompleted(i + 1)
                        .sectionResult(sectionResult)
                        .build());
            }

            PolicyAnalysisReport report = buildReport(taskId, fileName, language, results,
                    System.currentTimeMillis() - startTime);
            persistResult(taskId, report);

            eventBus.publish(TaskEvent.builder()
                    .taskId(taskId)
                    .type(TaskEventType.COMPLETED)
                    .timestamp(Instant.now())
                    .message("Analysis complete: " + report.totalViolations() + " violation(s) across "
                            + total + " section(s)")
                    .report(report)
                    .build());

        } catch (Exception e) {
            log.error("Async processing failed for taskId={}: {}", taskId, e.getMessage(), e);
            persistFailure(taskId, e);
            eventBus.publish(TaskEvent.builder()
                    .taskId(taskId)
                    .type(TaskEventType.FAILED)
                    .timestamp(Instant.now())
                    .message("Analysis failed")
                    .errorMessage(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build());
        }
    }

    private PolicyAnalysisReport buildReport(String taskId, String fileName, String language,
                                             List<ComplianceAnalysisResult> results, long totalTime) {
        int violationCount = 0;
        // The OverallRisk enum is declared HIGH, MEDIUM, LOW, so a *smaller* ordinal
        // means *higher* severity. Start at LOW (least severe) and escalate as we
        // see worse — comparison is `<`, not `>`.
        OverallRisk aggregate = OverallRisk.LOW;
        for (ComplianceAnalysisResult r : results) {
            violationCount += r.violations() == null ? 0 : r.violations().size();
            if (r.overallRisk() != null && r.overallRisk().ordinal() < aggregate.ordinal()) {
                aggregate = r.overallRisk();
            }
        }
        return PolicyAnalysisReport.builder()
                .taskId(taskId)
                .fileName(fileName)
                .language(language)
                .sectionResults(results)
                .aggregateRisk(aggregate)
                .totalViolations(violationCount)
                .totalProcessingTimeMs(totalTime)
                .build();
    }

    private void persistResult(String taskId, PolicyAnalysisReport report) {
        try {
            String json = objectMapper.writeValueAsString(report);
            uploadRepository.updateResult(taskId, ProcessingStatus.COMPLETED, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize report for taskId={}", taskId, e);
            uploadRepository.updateStatus(taskId, ProcessingStatus.COMPLETED);
        }
    }

    private void persistFailure(String taskId, Exception e) {
        try {
            String json = objectMapper.writeValueAsString(java.util.Map.of(
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage() == null ? "" : e.getMessage()));
            uploadRepository.updateResult(taskId, ProcessingStatus.FAILED, json);
        } catch (JsonProcessingException ex) {
            uploadRepository.updateStatus(taskId, ProcessingStatus.FAILED);
        }
    }
}