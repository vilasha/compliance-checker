package org.maria.compliance.service;

import org.junit.jupiter.api.Test;
import org.maria.compliance.model.RegulatoryChunk;
import org.maria.compliance.model.ScoredRegulatoryChunk;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompliancePromptBuilderTest {

    private final CompliancePromptBuilder builder = new CompliancePromptBuilder();

    @Test
    void prompt_includes_policy_text_language_and_chunks() {
        ScoredRegulatoryChunk chunk = scored("FINMA Rundschreiben 2017/3", 2017, "de",
                "Art. 4", "Eigenmittel müssen dauerhaft verfügbar sein.", 0.82);

        String prompt = builder.buildCompliancePrompt("Capital may be temporarily reduced.", "en", List.of(chunk));

        assertThat(prompt)
                .contains("Capital may be temporarily reduced.")
                .contains("Language: en")
                .contains("FINMA Rundschreiben 2017/3")
                .contains("Art. 4")
                .contains("Eigenmittel müssen dauerhaft verfügbar sein.")
                .contains("Similarity: 0.82")
                .contains("\"violations\"")
                .contains("\"overallRisk\"")
                .contains("Return ONLY a JSON object");
    }

    @Test
    void prompt_with_empty_chunks_still_produces_valid_structure() {
        String prompt = builder.buildCompliancePrompt("text", "de", List.of());

        assertThat(prompt)
                .contains("# Regulatory Context")
                .contains("# Policy Section Under Review")
                .contains("# Your Task")
                .contains("# Output Format");
    }

    @Test
    void prompt_handles_null_language_gracefully() {
        String prompt = builder.buildCompliancePrompt("text", null, List.of());

        assertThat(prompt).contains("Language: unknown");
    }

    @Test
    void retry_prompt_appends_retry_instruction() {
        String base = "BASE_PROMPT_CONTENT";

        String retry = builder.buildRetryPrompt(base);

        assertThat(retry)
                .startsWith(base)
                .contains(CompliancePromptBuilder.RETRY_INSTRUCTION);
    }

    private ScoredRegulatoryChunk scored(String law, int year, String lang, String section,
                                         String text, double score) {
        RegulatoryChunk chunk = RegulatoryChunk.builder()
                .lawName(law)
                .year(year)
                .language(lang)
                .sectionPath(section)
                .originalText(text)
                .sourceUrl("https://example.org/doc.pdf")
                .build();
        return ScoredRegulatoryChunk.builder()
                .chunk(chunk)
                .relevanceScore(score)
                .foundByPerspectives(List.of("single_query"))
                .build();
    }
}