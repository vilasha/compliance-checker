package org.maria.compliance.service;

import org.junit.jupiter.api.Test;
import org.maria.compliance.model.ChunkResult;
import org.maria.compliance.model.PolicySection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PdfProcessingServiceTest {

    @Autowired
    private PdfProcessingService pdfProcessingService;

    @Test
    void shouldDetectSections() {
        String text = """
                Introduction text here.
                
                Art. 1 Capital Requirements
                This article discusses capital requirements.
                
                Art. 2 Risk Management
                This article discusses risk management.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).heading()).startsWith("Art. 1");
        assertThat(sections.get(1).heading()).startsWith("Art. 2");
    }

    @Test
    void shouldChunkText() {
        String text = "A".repeat(2500);

        List<ChunkResult> chunks = pdfProcessingService.chunkText(text, 1000, 200);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks.get(0).text()).hasSize(1000);
    }

    @Test
    void shouldHandleTextWithoutSections() {
        String text = "This is a document without any section headings. Just plain text.";

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Full Document");
    }

    @Test
    void shouldRespectChunkOverlap() {
        String text = "0123456789".repeat(150);

        List<ChunkResult> chunks = pdfProcessingService.chunkText(text, 500, 100);

        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void shouldDetectMultipleSectionTypes() {
        String text = """
                Preamble
                
                Art. 1 First Article
                Content of article 1.
                
                § 2 Second Section
                Content of section 2.
                
                Article 3 Third Part
                Content of article 3.
                
                Chapter 4 Fourth Chapter
                Content of chapter 4.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(4);
        assertThat(sections.get(0).heading()).contains("Art. 1");
        assertThat(sections.get(1).heading()).contains("§ 2");
        assertThat(sections.get(2).heading()).contains("Article 3");
        assertThat(sections.get(3).heading()).contains("Chapter 4");
    }
}