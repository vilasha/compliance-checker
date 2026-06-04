package org.maria.compliance.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maria.compliance.model.ProcessingStatus;
import org.maria.compliance.model.TaskEvent;
import org.maria.compliance.model.TaskEventType;
import org.maria.compliance.model.UserUpload;
import org.maria.compliance.repository.UserUploadRepository;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisOrchestratorImplTest {

    private AsyncAnalysisWorker worker;
    private TaskEventBus eventBus;
    private UserUploadRepository uploadRepository;
    private AnalysisOrchestratorImpl orchestrator;

    @BeforeEach
    void setUp() {
        worker = mock(AsyncAnalysisWorker.class);
        eventBus = mock(TaskEventBus.class);
        uploadRepository = mock(UserUploadRepository.class);
        orchestrator = new AnalysisOrchestratorImpl(worker, eventBus, uploadRepository, 10);
    }

    @Test
    void submit_returnsTaskIdAndPersistsUpload() {
        MockMultipartFile file = pdfFile("policy.pdf", new byte[]{1, 2, 3});

        String taskId = orchestrator.submit(file, "de", "Alice");

        assertThat(taskId).isNotBlank();
        ArgumentCaptor<UserUpload> captor = ArgumentCaptor.forClass(UserUpload.class);
        verify(uploadRepository).save(captor.capture());
        UserUpload saved = captor.getValue();
        assertThat(saved.taskId()).isEqualTo(taskId);
        assertThat(saved.username()).isEqualTo("Alice");
        assertThat(saved.fileName()).isEqualTo("policy.pdf");
        assertThat(saved.status()).isEqualTo(ProcessingStatus.UPLOADING);
    }

    @Test
    void submit_publishesUploadedEvent() {
        MockMultipartFile file = pdfFile("policy.pdf", new byte[]{1});

        orchestrator.submit(file, "de", "Alice");

        ArgumentCaptor<TaskEvent> event = ArgumentCaptor.forClass(TaskEvent.class);
        verify(eventBus, times(1)).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(TaskEventType.UPLOADED);
    }

    @Test
    void submit_delegatesToWorker() {
        MockMultipartFile file = pdfFile("policy.pdf", new byte[]{1, 2, 3});

        String taskId = orchestrator.submit(file, "en", "Alice");

        verify(worker, times(1)).run(eq(taskId), any(byte[].class), eq("policy.pdf"), eq("en"));
    }

    @Test
    void submit_rejectsEmptyFile() {
        MockMultipartFile file = pdfFile("policy.pdf", new byte[0]);
        assertThatThrownBy(() -> orchestrator.submit(file, "de", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        verify(worker, never()).run(anyString(), any(byte[].class), anyString(), anyString());
    }

    @Test
    void submit_rejectsNonPdfContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "policy.txt", "text/plain", new byte[]{1, 2});
        assertThatThrownBy(() -> orchestrator.submit(file, "de", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PDF");
        verify(worker, never()).run(anyString(), any(byte[].class), anyString(), anyString());
    }

    @Test
    void submit_rejectsOversizedFile() {
        byte[] big = new byte[11 * 1024 * 1024];
        MockMultipartFile file = pdfFile("big.pdf", big);
        assertThatThrownBy(() -> orchestrator.submit(file, "de", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        verify(worker, never()).run(anyString(), any(byte[].class), anyString(), anyString());
    }

    private MockMultipartFile pdfFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/pdf", content);
    }
}