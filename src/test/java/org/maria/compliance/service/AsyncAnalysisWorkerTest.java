package org.maria.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maria.compliance.model.*;
import org.maria.compliance.repository.UserUploadRepository;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncAnalysisWorkerTest {

    private PdfProcessingService pdfProcessingService;
    private SingleQueryRagService ragService;
    private TaskEventBus eventBus;
    private UserUploadRepository uploadRepository;
    private AsyncAnalysisWorker worker;

    @BeforeEach
    void setUp() {
        pdfProcessingService = mock(PdfProcessingService.class);
        ragService = mock(SingleQueryRagService.class);
        eventBus = mock(TaskEventBus.class);
        uploadRepository = mock(UserUploadRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        // 50 / 6000 mirror the production defaults for max-policy-sections and
        // max-section-chars; test sections are tiny, so the split path stays inert
        worker = new AsyncAnalysisWorker(pdfProcessingService, ragService, eventBus,
                uploadRepository, objectMapper, 50, 6000);
    }

    @Test
    void run_happyPath_emitsExpectedEventSequence() {
        String taskId = "task-happy";
        when(pdfProcessingService.extractText(any(byte[].class), anyString())).thenReturn("policy body");
        when(pdfProcessingService.detectSections("policy body")).thenReturn(List.of(
                PolicySection.builder().heading("I. Intro").content("intro body").build(),
                PolicySection.builder().heading("II. Risks").content("risks body").build()
        ));
        when(ragService.analyze(anyString(), eq("de"))).thenReturn(violationResult());

        worker.run(taskId, new byte[]{1}, "policy.pdf", "de");

        verify(uploadRepository).updateStatus(taskId, ProcessingStatus.PROCESSING);
        verify(uploadRepository).updateResult(eq(taskId), eq(ProcessingStatus.COMPLETED), anyString());

        ArgumentCaptor<TaskEvent> events = ArgumentCaptor.forClass(TaskEvent.class);
        verify(eventBus, atLeastOnce()).publish(events.capture());
        List<TaskEventType> types = events.getAllValues().stream().map(TaskEvent::type).toList();
        assertThat(types).containsSequence(
                TaskEventType.EXTRACTING,
                TaskEventType.SECTIONS_DETECTED,
                TaskEventType.SECTION_STARTED,
                TaskEventType.SECTION_ANALYZED,
                TaskEventType.SECTION_STARTED,
                TaskEventType.SECTION_ANALYZED,
                TaskEventType.COMPLETED);

        List<TaskEvent> sectionEvents = events.getAllValues().stream()
                .filter(e -> e.type() == TaskEventType.SECTION_ANALYZED)
                .toList();
        assertThat(sectionEvents.get(0).sectionsCompleted()).isEqualTo(1);
        assertThat(sectionEvents.get(0).sectionsTotal()).isEqualTo(2);
        assertThat(sectionEvents.get(1).sectionsCompleted()).isEqualTo(2);
    }

    @Test
    void run_aggregateRiskTakesHighestSeverity() {
        // OverallRisk enum order is HIGH, MEDIUM, LOW — lower ordinal = higher severity.
        // LOW + HIGH must produce aggregate HIGH.
        String taskId = "task-risk";
        when(pdfProcessingService.extractText(any(byte[].class), anyString())).thenReturn("body");
        when(pdfProcessingService.detectSections("body")).thenReturn(List.of(
                PolicySection.builder().heading("§ 1").content("s1").build(),
                PolicySection.builder().heading("§ 2").content("s2").build()
        ));
        when(ragService.analyze("s1", "de")).thenReturn(resultWithRisk(OverallRisk.LOW));
        when(ragService.analyze("s2", "de")).thenReturn(resultWithRisk(OverallRisk.HIGH));

        worker.run(taskId, new byte[]{1}, "f.pdf", "de");

        ArgumentCaptor<TaskEvent> events = ArgumentCaptor.forClass(TaskEvent.class);
        verify(eventBus, atLeastOnce()).publish(events.capture());
        TaskEvent completed = events.getAllValues().stream()
                .filter(e -> e.type() == TaskEventType.COMPLETED)
                .findFirst().orElseThrow();
        assertThat(completed.report().aggregateRisk()).isEqualTo(OverallRisk.HIGH);
        assertThat(completed.report().totalViolations()).isEqualTo(2);
    }

    @Test
    void run_capCountsAnalysisUnitsAfterSplitting_notDetectedSections() {
        // One heading-less oversized section fans out into many parts; the
        // max-policy-sections cap must bound the *units analyzed*, not the
        // detected-heading count (which here is 1)
        String taskId = "task-cap";
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AsyncAnalysisWorker cappedWorker = new AsyncAnalysisWorker(pdfProcessingService, ragService,
                eventBus, uploadRepository, objectMapper, 2, 10);

        String oversized = "x".repeat(50);
        when(pdfProcessingService.extractText(any(byte[].class), anyString())).thenReturn(oversized);
        when(pdfProcessingService.detectSections(oversized)).thenReturn(List.of(
                PolicySection.builder().heading("Full Document").content(oversized)
                        .startPosition(0).endPosition(oversized.length()).build()
        ));
        when(pdfProcessingService.chunkText(eq(oversized), eq(10), anyInt())).thenReturn(List.of(
                chunk("part-a", 0, 10),
                chunk("part-b", 10, 20),
                chunk("part-c", 20, 30),
                chunk("part-d", 30, 40),
                chunk("part-e", 40, 50)
        ));
        when(ragService.analyze(anyString(), eq("de"))).thenReturn(violationResult());

        cappedWorker.run(taskId, new byte[]{1}, "huge.pdf", "de");

        verify(ragService, times(2)).analyze(anyString(), eq("de"));

        ArgumentCaptor<TaskEvent> events = ArgumentCaptor.forClass(TaskEvent.class);
        verify(eventBus, atLeastOnce()).publish(events.capture());
        TaskEvent detectedEvent = events.getAllValues().stream()
                .filter(e -> e.type() == TaskEventType.SECTIONS_DETECTED)
                .findFirst().orElseThrow();
        assertThat(detectedEvent.sectionsTotal()).isEqualTo(2);
        assertThat(detectedEvent.message()).contains("NOT covered");
    }

    private ChunkResult chunk(String text, int start, int end) {
        return ChunkResult.builder()
                .text(text)
                .chunkIndex(0)
                .startPosition(start)
                .endPosition(end)
                .build();
    }

    @Test
    void run_failure_emitsFailedEventAndPersistsStatus() {
        String taskId = "task-fail";
        when(pdfProcessingService.extractText(any(byte[].class), anyString()))
                .thenThrow(new RuntimeException("pdfbox boom"));

        worker.run(taskId, new byte[]{1}, "f.pdf", "de");

        ArgumentCaptor<TaskEvent> events = ArgumentCaptor.forClass(TaskEvent.class);
        verify(eventBus, atLeastOnce()).publish(events.capture());
        TaskEvent last = events.getAllValues().get(events.getAllValues().size() - 1);
        assertThat(last.type()).isEqualTo(TaskEventType.FAILED);
        assertThat(last.errorMessage()).contains("pdfbox boom");

        verify(uploadRepository).updateResult(eq(taskId), eq(ProcessingStatus.FAILED), anyString());
        verify(ragService, times(0)).analyze(anyString(), anyString());
    }

    private ComplianceAnalysisResult violationResult() {
        return ComplianceAnalysisResult.builder()
                .policySection("x")
                .violations(List.of(PerspectiveViolation.builder()
                        .perspective(Perspective.STRICT_LEGAL)
                        .severity(Severity.MEDIUM)
                        .regulatoryText("...")
                        .violationDetail("...")
                        .source("...")
                        .build()))
                .overallRisk(OverallRisk.MEDIUM)
                .recommendation("...")
                .processingTimeMs(100)
                .build();
    }

    private ComplianceAnalysisResult resultWithRisk(OverallRisk risk) {
        return ComplianceAnalysisResult.builder()
                .policySection("x")
                .violations(List.of(PerspectiveViolation.builder()
                        .perspective(Perspective.STRICT_LEGAL)
                        .severity(Severity.MEDIUM)
                        .regulatoryText("...")
                        .violationDetail("...")
                        .source("...")
                        .build()))
                .overallRisk(risk)
                .recommendation("...")
                .processingTimeMs(50)
                .build();
    }
}