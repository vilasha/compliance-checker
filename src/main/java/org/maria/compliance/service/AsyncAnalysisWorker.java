package org.maria.compliance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.*;
import org.maria.compliance.repository.UserUploadRepository;
import org.springframework.beans.factory.annotation.Value;
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
 * both work but obscure the intent)
 */
@Service
@Slf4j
public class AsyncAnalysisWorker {

    // Overlap between parts of a split section, so a clause straddling the cut
    // is fully visible in at least one part instead of being analyzed half-blind.
    private static final int SPLIT_OVERLAP_CHARS = 200;

    private final PdfProcessingService pdfProcessingService;
    private final SingleQueryRagService ragService;
    private final TaskEventBus eventBus;
    private final UserUploadRepository uploadRepository;
    private final ObjectMapper objectMapper;
    private final int maxPolicySections;
    private final int maxSectionChars;

    public AsyncAnalysisWorker(PdfProcessingService pdfProcessingService,
                               SingleQueryRagService ragService,
                               TaskEventBus eventBus,
                               UserUploadRepository uploadRepository,
                               ObjectMapper objectMapper,
                               @Value("${compliance.rag.max-policy-sections:50}") int maxPolicySections,
                               @Value("${compliance.rag.max-section-chars:6000}") int maxSectionChars) {
        this.pdfProcessingService = pdfProcessingService;
        this.ragService = ragService;
        this.eventBus = eventBus;
        this.uploadRepository = uploadRepository;
        this.objectMapper = objectMapper;
        this.maxPolicySections = maxPolicySections;
        this.maxSectionChars = maxSectionChars;
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
            List<PolicySection> detected = pdfProcessingService.detectSections(text);
            Prepared prepared = prepareSections(detected);
            List<PolicySection> sections = prepared.sections();
            int total = sections.size();

            eventBus.publish(TaskEvent.builder()
                    .taskId(taskId)
                    .type(TaskEventType.SECTIONS_DETECTED)
                    .timestamp(Instant.now())
                    .message(sectionsDetectedMessage(detected.size(), prepared))
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

    /**
     * Outcome of section preparation: the analysis units to run, plus whether the
     * {@code max-policy-sections} cap cut anything off (needed for honest progress
     * messaging — a truncated scan must say so)
     */
    private record Prepared(List<PolicySection> sections, boolean truncated) {
    }

    /**
     * Enforces the two size limits that protect the LLM pipeline:
     * <ul>
     *   <li>{@code max-section-chars} splits oversized sections — when heading detection
     *       finds nothing, "one section" is the whole document, which silently blows the
     *       embedding-query and chat-context budgets. Splitting keeps full coverage,
     *       unlike truncation, which a compliance tool must never do silently;</li>
     *   <li>{@code max-policy-sections} caps the number of analysis units <em>after</em>
     *       splitting, bounding total LLM calls and therefore runtime. The cap counts
     *       units, not detected headings: a single heading-less 500&nbsp;KB document
     *       fans out into many parts, and counting before the split would let it
     *       blow straight through the limit</li>
     * </ul>
     */
    private Prepared prepareSections(List<PolicySection> detected) {
        List<PolicySection> prepared = new ArrayList<>();

        for (PolicySection section : detected) {
            if (prepared.size() >= maxPolicySections) {
                return new Prepared(prepared, true);
            }
            if (section.content() == null || section.content().length() <= maxSectionChars) {
                prepared.add(renumbered(section, prepared.size() + 1));
                continue;
            }
            List<ChunkResult> parts = pdfProcessingService.chunkText(
                    section.content(), maxSectionChars, SPLIT_OVERLAP_CHARS);
            for (int p = 0; p < parts.size(); p++) {
                if (prepared.size() >= maxPolicySections) {
                    return new Prepared(prepared, true);
                }
                ChunkResult part = parts.get(p);
                prepared.add(PolicySection.builder()
                        .sectionNumber(prepared.size() + 1)
                        .heading(section.heading() + " (part " + (p + 1) + "/" + parts.size() + ")")
                        .content(part.text())
                        .startPosition(section.startPosition() + part.startPosition())
                        .endPosition(section.startPosition() + part.endPosition())
                        .build());
            }
        }
        return new Prepared(prepared, false);
    }

    private PolicySection renumbered(PolicySection section, int number) {
        return PolicySection.builder()
                .sectionNumber(number)
                .heading(section.heading())
                .content(section.content())
                .startPosition(section.startPosition())
                .endPosition(section.endPosition())
                .build();
    }

    private String sectionsDetectedMessage(int detectedCount, Prepared prepared) {
        int preparedCount = prepared.sections().size();
        if (prepared.truncated()) {
            return "Detected " + detectedCount + " section(s); analyzing the first "
                    + preparedCount + " analysis unit(s) (max-policy-sections limit) — "
                    + "the remainder of the document is NOT covered by this report";
        }
        if (detectedCount == preparedCount) {
            return "Detected " + detectedCount + " section(s)";
        }
        return "Detected " + detectedCount + " section(s), split into "
                + preparedCount + " analysis unit(s) due to section length";
    }

    private PolicyAnalysisReport buildReport(String taskId, String fileName, String language,
                                             List<ComplianceAnalysisResult> results, long totalTime) {
        int violationCount = 0;
        // The OverallRisk enum is declared HIGH, MEDIUM, UNKNOWN, LOW, so a *smaller*
        // ordinal means *higher* severity. Start at LOW (least severe) and escalate as
        // we see worse — comparison is `<`, not `>`. UNKNOWN ranks above LOW so that
        // unanalyzable sections surface in the aggregate instead of reading as "fine".
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