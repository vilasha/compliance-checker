package org.maria.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.junit.jupiter.api.Test;
import org.maria.compliance.model.ComplianceAnalysisResult;
import org.maria.compliance.model.OverallRisk;
import org.maria.compliance.model.Perspective;
import org.maria.compliance.model.Severity;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(
        classes = {SingleQueryRagServiceImpl.class, CompliancePromptBuilder.class,
                SingleQueryRagServiceImplTest.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class SingleQueryRagServiceImplTest {

    private static final String CHAT_MODEL_NAME = "qwen2.5:3b";
    private static final int PARSE_RETRY_MAX = 4;
    private static final int TOP_K = 5;
    private static final double MIN_SCORE = 0.7;

    private static final String VALID_JSON_RESPONSE = """
            {
              "violations": [
                {
                  "severity": "HIGH",
                  "regulatoryText": "Eigenmittel müssen dauerhaft verfügbar sein.",
                  "violationDetail": "Policy permits temporary reduction of capital.",
                  "source": "FINMA Rundschreiben 2017/3, Art. 4"
                }
              ],
              "overallRisk": "HIGH",
              "recommendation": "Remove the temporary reduction clause."
            }
            """;

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockitoBean
    private EmbeddingService embeddingService;
    @MockitoBean
    private ChatModel chatModel;
    @Autowired
    private SingleQueryRagServiceImpl service;

    @Test
    void analyze_happyPath_singleLlmCallReturnsParsedResult() {
        givenRetrievalReturns(matchWith("law_name", "FINMA", "year", 2017, "language", "de",
                "section_path", "Art. 4", 0.85, "Verbatim chunk text."));
        when(chatModel.chat(anyString())).thenReturn(VALID_JSON_RESPONSE);

        ComplianceAnalysisResult result = service.analyze("Policy text", "de");

        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0).severity()).isEqualTo(Severity.HIGH);
        assertThat(result.violations().get(0).perspective()).isEqualTo(Perspective.STRICT_LEGAL);
        assertThat(result.violations().get(0).regulatoryText())
                .isEqualTo("Eigenmittel müssen dauerhaft verfügbar sein.");
        assertThat(result.overallRisk()).isEqualTo(OverallRisk.HIGH);
        assertThat(result.recommendation()).contains("temporary reduction");
        verify(chatModel, times(1)).chat(anyString());
    }

    @Test
    void analyze_emptyRetrieval_skipsLlmEntirely() {
        givenRetrievalReturns();

        ComplianceAnalysisResult result = service.analyze("Policy text", "de");

        assertThat(result.violations()).isEmpty();
        assertThat(result.overallRisk()).isEqualTo(OverallRisk.LOW);
        assertThat(result.recommendation()).contains("No relevant regulatory context");
        verify(chatModel, never()).chat(anyString());
    }

    @Test
    void analyze_extractsJsonFromFencedBlock() {
        givenRetrievalReturns(matchWith("law_name", "FINMA", "year", 2017, "language", "de",
                "section_path", "Art. 4", 0.85, "chunk"));
        String fenced = "Here is the analysis:\n```json\n" + VALID_JSON_RESPONSE + "\n```\nDone.";
        when(chatModel.chat(anyString())).thenReturn(fenced);

        ComplianceAnalysisResult result = service.analyze("Policy text", "de");

        assertThat(result.violations()).hasSize(1);
        verify(chatModel, times(1)).chat(anyString());
    }

    @Test
    void analyze_parseFails_retriesUntilSuccess() {
        givenRetrievalReturns(matchWith("law_name", "FINMA", "year", 2017, "language", "de",
                "section_path", "Art. 4", 0.85, "chunk"));
        when(chatModel.chat(anyString()))
                .thenReturn("This is not JSON.")
                .thenReturn("Still not JSON.")
                .thenReturn(VALID_JSON_RESPONSE);

        ComplianceAnalysisResult result = service.analyze("Policy text", "de");

        assertThat(result.violations()).hasSize(1);
        assertThat(result.overallRisk()).isEqualTo(OverallRisk.HIGH);
        verify(chatModel, times(3)).chat(anyString());
    }

    @Test
    void analyze_allAttemptsFail_returnsFallbackResult() {
        givenRetrievalReturns(matchWith("law_name", "FINMA", "year", 2017, "language", "de",
                "section_path", "Art. 4", 0.85, "chunk"));
        when(chatModel.chat(anyString())).thenReturn("Not JSON ever.");

        ComplianceAnalysisResult result = service.analyze("Policy text", "de");

        // UNKNOWN, not LOW: exhausted parse retries mean "could not analyze",
        // which must never aggregate as "no concern" in the report.
        assertThat(result.violations()).isEmpty();
        assertThat(result.overallRisk()).isEqualTo(OverallRisk.UNKNOWN);
        assertThat(result.recommendation())
                .contains("could not be parsed as JSON")
                .contains(String.valueOf(1 + PARSE_RETRY_MAX));
        // 1 initial attempt + parseRetryMax retries
        verify(chatModel, times(1 + PARSE_RETRY_MAX)).chat(anyString());
    }

    @Test
    void analyze_retryPromptIncludesRetryInstruction() {
        givenRetrievalReturns(matchWith("law_name", "FINMA", "year", 2017, "language", "de",
                "section_path", "Art. 4", 0.85, "chunk"));
        when(chatModel.chat(anyString()))
                .thenReturn("garbage")
                .thenReturn(VALID_JSON_RESPONSE);

        service.analyze("Policy text", "de");

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(chatModel, times(2)).chat(prompts.capture());
        List<String> sentPrompts = prompts.getAllValues();
        assertThat(sentPrompts.get(0)).doesNotContain(CompliancePromptBuilder.RETRY_INSTRUCTION);
        assertThat(sentPrompts.get(1)).contains(CompliancePromptBuilder.RETRY_INSTRUCTION);
    }

    @Test
    void analyze_blankLanguage_usesUnfilteredSearch() {
        givenUnfilteredRetrievalReturns(matchWith("law_name", "FINMA", "year", 2017, "language", "de",
                "section_path", "Art. 4", 0.85, "chunk"));
        when(chatModel.chat(anyString())).thenReturn(VALID_JSON_RESPONSE);

        service.analyze("Policy text", "");

        verify(embeddingService, times(1))
                .searchSimilar(anyString(), anyInt(), anyDouble());
        verify(embeddingService, never())
                .searchSimilar(anyString(), anyInt(), anyDouble(), anyString());
    }

    @Test
    void analyze_unexpectedException_returnsErrorResult() {
        when(embeddingService.searchSimilar(anyString(), anyInt(), anyDouble(), anyString()))
                .thenThrow(new RuntimeException("embedding service down"));

        ComplianceAnalysisResult result = service.analyze("Policy text", "de");

        assertThat(result.violations()).isEmpty();
        assertThat(result.overallRisk()).isEqualTo(OverallRisk.UNKNOWN);
        assertThat(result.recommendation()).contains("Compliance analysis failed");
        verify(chatModel, never()).chat(anyString());
    }

    // ----- test helpers -----

    @SafeVarargs
    private void givenRetrievalReturns(EmbeddingMatch<TextSegment>... matches) {
        EmbeddingSearchResult<TextSegment> searchResult = new EmbeddingSearchResult<>(List.of(matches));
        when(embeddingService.searchSimilar(anyString(), anyInt(), anyDouble(), anyString()))
                .thenReturn(searchResult);
    }

    @SafeVarargs
    private void givenUnfilteredRetrievalReturns(EmbeddingMatch<TextSegment>... matches) {
        EmbeddingSearchResult<TextSegment> searchResult = new EmbeddingSearchResult<>(List.of(matches));
        when(embeddingService.searchSimilar(anyString(), anyInt(), anyDouble()))
                .thenReturn(searchResult);
    }

    /**
     * Builds an EmbeddingMatch with arbitrary metadata pairs. Args after score and text
     * are flat key/value pairs (key1, val1, key2, val2, ...).
     */
    private EmbeddingMatch<TextSegment> matchWith(Object... kvThenScoreThenText) {
        // Last two positional args are score (double) and text (String); preceding pairs are metadata.
        // For clarity we read fixed positions in tests above instead of dynamic parsing.
        Map<String, Object> meta = new HashMap<>();
        int metaEnd = kvThenScoreThenText.length - 2;
        for (int i = 0; i < metaEnd; i += 2) {
            meta.put((String) kvThenScoreThenText[i], kvThenScoreThenText[i + 1]);
        }
        double score = (double) kvThenScoreThenText[metaEnd];
        String text = (String) kvThenScoreThenText[metaEnd + 1];

        Metadata metadata = new Metadata();
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Integer i) {
                metadata.put(e.getKey(), i);
            } else {
                metadata.put(e.getKey(), v.toString());
            }
        }
        TextSegment segment = TextSegment.from(text, metadata);
        return new EmbeddingMatch<>(score, "embedding-id-" + score, null, segment);
    }
}