package org.maria.compliance.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.maria.compliance.model.ChunkResult;
import org.maria.compliance.model.PolicySection;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PdfProcessingServiceTest {

    @InjectMocks
    private PdfProcessingServiceImpl pdfProcessingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(pdfProcessingService, "sectionDetectionEnabled", true);
        ReflectionTestUtils.setField(pdfProcessingService, "headingPatterns",
                "^[ \\t]*(?![0-9]+\\.[ \\t]+(?:Januar|January|Februar|February|März|March|April|Mai|May|Juni|June|Juli|July|August|September|Oktober|October|November|Dezember|December)\\b)(?:(?:[IVX]+|[0-9]+)\\.(?:[0-9]+(?:\\.[0-9]+)*\\.?)?[ \\t]+\\p{Lu}|§[ \\t]*[0-9]+)");
        ReflectionTestUtils.setField(pdfProcessingService, "defaultChunkSize", 1000);
        ReflectionTestUtils.setField(pdfProcessingService, "defaultChunkOverlap", 200);
    }

    @Test
    void detectSections_findsRomanNumeralHeadings() {
        String text = """
                Preamble paragraph not part of any section.
                
                I. Allgemeine Ausführungen
                Content of the first section.
                
                II. Risikoanalyse
                Content of the second section.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).heading()).startsWith("I. Allgemeine Ausführungen");
        assertThat(sections.get(1).heading()).startsWith("II. Risikoanalyse");
    }

    @Test
    void detectSections_findsSubNumberedRomanHeadings() {
        String text = """
                III. Hauptteil
                Introductory content for chapter III.
                
                III.3.1 Einhaltung der Geldwäschereivorschriften
                Detailed AML content.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).heading()).startsWith("III. Hauptteil");
        assertThat(sections.get(1).heading())
                .startsWith("III.3.1 Einhaltung der Geldwäschereivorschriften");
    }

    @Test
    void detectSections_findsNumericSectionHeadings() {
        String text = """
                Preamble.
                
                3. Risiken und Massnahmen
                Section 3 content.
                
                3.1 Identifikation der Vertragspartei
                Subsection content.
                
                4. Schlussbestimmungen
                Closing content.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).heading()).startsWith("3. Risiken und Massnahmen");
        assertThat(sections.get(1).heading()).startsWith("3.1 Identifikation");
        assertThat(sections.get(2).heading()).startsWith("4. Schlussbestimmungen");
    }

    @Test
    void detectSections_findsParagraphMarkerHeadings() {
        String text = """
                Preamble.
                
                § 1 Geltungsbereich
                Content of paragraph 1.
                
                § 12 Aufzeichnungspflicht
                Content of paragraph 12.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).heading()).startsWith("§ 1 Geltungsbereich");
        assertThat(sections.get(1).heading()).startsWith("§ 12 Aufzeichnungspflicht");
    }

    @Test
    void detectSections_ignoresInlineArticleReferences() {
        // The old regex split this text at every "Art." / "Artikel" / "Article" line start,
        // producing four bogus "sections" centred on inline cross-references.
        // The fixed regex must only detect the actual heading.
        String text = """
                I. Einleitung
                Diese Bestimmung knüpft an verschiedene Vorschriften an.
                Art. 6 Abs. 1 GwG schreibt vor, dass Finanzintermediäre
                die Identität ihrer Vertragspartner überprüfen müssen.
                Artikel 4 der Solvency-II-Richtlinie definiert die Anforderungen.
                Article 132 of the directive sets out the prudent person principle.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).startsWith("I. Einleitung");
    }

    @Test
    void detectSections_rejectsLowercaseAfterMarker() {
        // "II. risikoanalyse" (lowercase after the dot) is almost certainly not a heading;
        // requiring an uppercase letter after the marker filters such false positives.
        String text = """
                Some prose where a number-like sequence appears.
                II. risikoanalyse wird hier nicht als Überschrift erkannt.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Full Document");
    }

    @Test
    void detectSections_rejectsGermanCalendarDatesAtLineStart() {
        // PDF text wrap can place dates like "1. Januar des Folgejahres..." at line starts.
        // Without the date-exclusion lookahead, these match the numeric heading pattern
        // (since month names are capitalized in German). The lookahead must reject them.
        String text = """
                Tarifänderungen, die auf den
                1. Januar des Folgejahres vorgenommen werden sollen, müssen bis spätestens
                30. April des laufenden Jahres eingereicht werden.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Full Document");
    }

    @Test
    void detectSections_rejectsEnglishCalendarDatesAtLineStart() {
        String text = """
                The amendment shall enter into force on
                1. January of the following year, provided that submission occurs by
                30. April of the current year.
                """;

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Full Document");
    }

    @Test
    void detectSections_returnsFullDocumentWhenNoHeadingsFound() {
        String text = "This is a document without any section headings. Just plain text.";

        List<PolicySection> sections = pdfProcessingService.detectSections(text);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Full Document");
    }

    @Test
    void chunkText_producesFixedSizeChunks() {
        String text = "A".repeat(2500);

        List<ChunkResult> chunks = pdfProcessingService.chunkText(text, 1000, 200);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks.get(0).text()).hasSize(1000);
    }

    @Test
    void chunkText_respectsOverlap() {
        String text = "0123456789".repeat(150);

        List<ChunkResult> chunks = pdfProcessingService.chunkText(text, 500, 100);

        assertThat(chunks).hasSizeGreaterThan(1);
    }
}