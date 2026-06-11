package org.maria.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class SingleQueryRagServiceImpl implements SingleQueryRagService {

    // Captures both ```json ... ``` and plain ``` ... ``` fenced JSON blocks.
    private static final Pattern FENCED_JSON = Pattern.compile(
            "```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private static final String SINGLE_QUERY_PERSPECTIVE_TAG = "single_query";

    private final EmbeddingService embeddingService;
    private final ChatModel chatModel;
    private final CompliancePromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    @Value("${compliance.ollama.chat-model:qwen2.5:3b}")
    private String chatModelName;
    @Value("${compliance.rag.top-k-results:5}")
    private int topK;
    @Value("${compliance.rag.similarity-threshold:0.7}")
    private double minScore;
    @Value("${compliance.rag.parse-retry-max:4}")
    private int parseRetryMax;

    @Override
    public ComplianceAnalysisResult analyze(String policyText, String language) {
        long startTime = System.currentTimeMillis();

        try {
            List<ScoredRegulatoryChunk> chunks = retrieveContext(policyText, language, topK);

            if (chunks.isEmpty()) {
                log.info("No regulatory context above threshold {}, skipping LLM call", minScore);
                return emptyContextResult(policyText, System.currentTimeMillis() - startTime);
            }

            String basePrompt = promptBuilder.buildCompliancePrompt(policyText, language, chunks);
            int totalAttempts = 1 + parseRetryMax;
            String lastRaw = null;

            for (int attempt = 1; attempt <= totalAttempts; attempt++) {
                String prompt = (attempt == 1) ? basePrompt : promptBuilder.buildRetryPrompt(basePrompt);
                LlmAnalysisResponse raw = callLlm(prompt);
                lastRaw = raw.rawText();

                Optional<ComplianceAnalysisResult> parsed = tryParseResponse(raw, policyText, System.currentTimeMillis() - startTime);

                if (parsed.isPresent()) {
                    if (attempt > 1) {
                        log.info("JSON parse succeeded on attempt {}/{}", attempt, totalAttempts);
                    }
                    return parsed.get();
                }

                log.warn("JSON parse failed on attempt {}/{} — raw response (first 300 chars): {}",
                        attempt, totalAttempts, truncate(raw.rawText(), 300));
            }

            log.error("JSON parse failed after {} attempts. Final raw response: {}", totalAttempts, lastRaw);
            return parseFailureResult(policyText, totalAttempts, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Unexpected error during single-query analysis: {}", e.getMessage(), e);
            return unexpectedErrorResult(policyText, e, System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public List<ScoredRegulatoryChunk> retrieveContext(String policyText, String language, int topK) {
        EmbeddingSearchResult<TextSegment> searchResult = (language != null && !language.isBlank())
                ? embeddingService.searchSimilar(policyText, topK, minScore, language)
                : embeddingService.searchSimilar(policyText, topK, minScore);

        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        log.info("Retrieved {} chunks (language={}, topK={}, minScore={})",
                matches.size(), language, topK, minScore);

        List<ScoredRegulatoryChunk> result = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            result.add(toScoredChunk(match));
        }
        return result;
    }

    @Override
    public LlmAnalysisResponse callLlm(String prompt) {
        long start = System.currentTimeMillis();
        String response = chatModel.chat(prompt);
        log.debug("LLM call completed in {}ms ({} chars)",
                System.currentTimeMillis() - start, response != null ? response.length() : 0);
        // tokensUsed=0 here because ChatModel.chat(String) discards usage metadata.
        // Step 12 (observability) will switch to ChatRequest to capture token counts.
        return LlmAnalysisResponse.builder()
                .rawText(response)
                .modelUsed(chatModelName)
                .tokensUsed(0)
                .build();
    }

    @Override
    public Optional<ComplianceAnalysisResult> tryParseResponse(LlmAnalysisResponse raw,
                                                               String policyText,
                                                               long elapsedMs) {
        if (raw == null || raw.rawText() == null) {
            return Optional.empty();
        }

        String extracted = extractJson(raw.rawText());
        if (extracted == null) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(extracted);
            List<PerspectiveViolation> violations = parseViolations(root.path("violations"));
            OverallRisk risk = parseRisk(root.path("overallRisk").asText(""));
            String recommendation = root.path("recommendation").asText("");

            return Optional.of(ComplianceAnalysisResult.builder()
                    .policySection(policyText)
                    .violations(violations)
                    .overallRisk(risk)
                    .recommendation(recommendation)
                    .processingTimeMs(elapsedMs)
                    .build());

        } catch (Exception e) {
            log.debug("JSON parse exception: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private ScoredRegulatoryChunk toScoredChunk(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Metadata metadata = segment.metadata();

        RegulatoryChunk chunk = RegulatoryChunk.builder()
                .lawName(metadata.getString("law_name"))
                .year(metadata.getInteger("year"))
                .language(metadata.getString("language"))
                .sectionPath(metadata.getString("section_path"))
                .originalText(segment.text())
                .sourceUrl(metadata.getString("source_url"))
                .build();

        return ScoredRegulatoryChunk.builder()
                .chunk(chunk)
                .relevanceScore(match.score())
                .foundByPerspectives(List.of(SINGLE_QUERY_PERSPECTIVE_TAG))
                .build();
    }

    private List<PerspectiveViolation> parseViolations(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<PerspectiveViolation> result = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            result.add(PerspectiveViolation.builder()
                    .perspective(Perspective.STRICT_LEGAL)
                    .severity(parseSeverity(node.path("severity").asText("LOW")))
                    .regulatoryText(node.path("regulatoryText").asText(""))
                    .violationDetail(node.path("violationDetail").asText(""))
                    .source(node.path("source").asText(""))
                    .build());
        }
        return result;
    }

    // Both parsers fail toward UNKNOWN, never LOW: a severity the LLM made up
    // (or omitted) means "we don't know", and in a compliance report "we don't
    // know" must not be rendered as "no concern".
    private Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Severity.UNKNOWN;
        }
    }

    private OverallRisk parseRisk(String value) {
        try {
            return OverallRisk.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OverallRisk.UNKNOWN;
        }
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher fenced = FENCED_JSON.matcher(text);
        if (fenced.find()) {
            return fenced.group(1);
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private ComplianceAnalysisResult emptyContextResult(String policyText, long elapsedMs) {
        return ComplianceAnalysisResult.builder()
                .policySection(policyText)
                .violations(List.of())
                .overallRisk(OverallRisk.LOW)
                .recommendation("No relevant regulatory context found above similarity threshold " + minScore + ".")
                .processingTimeMs(elapsedMs)
                .build();
    }

    private ComplianceAnalysisResult parseFailureResult(String policyText, int totalAttempts, long elapsedMs) {
        return ComplianceAnalysisResult.builder()
                .policySection(policyText)
                .violations(List.of())
                .overallRisk(OverallRisk.UNKNOWN)
                .recommendation("LLM response could not be parsed as JSON after " + totalAttempts
                        + " attempts. Manual review required.")
                .processingTimeMs(elapsedMs)
                .build();
    }

    private ComplianceAnalysisResult unexpectedErrorResult(String policyText, Exception e, long elapsedMs) {
        return ComplianceAnalysisResult.builder()
                .policySection(policyText)
                .violations(List.of())
                .overallRisk(OverallRisk.UNKNOWN)
                .recommendation("Compliance analysis failed (" + e.getClass().getSimpleName()
                        + ") " + e.getMessage())
                .processingTimeMs(elapsedMs)
                .build();
    }
}