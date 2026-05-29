package org.maria.compliance.service;

import org.maria.compliance.model.RegulatoryChunk;
import org.maria.compliance.model.ScoredRegulatoryChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Builds the compliance analysis prompt sent to the LLM
 * <p>
 * Kept as a separate component so prompt iteration (the highest-leverage tuning surface
 * in a RAG system) can happen without touching orchestration logic, and so unit tests
 * can assert on the prompt structure directly
 */
@Component
public class CompliancePromptBuilder {

    static final String RETRY_INSTRUCTION =
            "Your previous response could not be parsed as JSON. " +
                    "Return ONLY the JSON object — no preamble, no explanation, no markdown code fences.";

    private static final String JSON_SCHEMA = """
            {
              "violations": [
                {
                  "severity": "HIGH | MEDIUM | LOW",
                  "regulatoryText": "Verbatim quote from one of the excerpts above",
                  "violationDetail": "Concise description of the compliance concern",
                  "source": "Law name, year, and section (e.g., 'FINMA Rundschreiben 2017/3, Art. 4')"
                }
              ],
              "overallRisk": "HIGH | MEDIUM | LOW",
              "recommendation": "Concrete action the policy author should take"
            }""";

    public String buildCompliancePrompt(String policyText, String language, List<ScoredRegulatoryChunk> chunks) {
        StringBuilder sb = new StringBuilder(8192);

        sb.append("You are a regulatory compliance analyst reviewing an insurance policy section ")
                .append("against Swiss and EU insurance/reinsurance regulations.\n\n")
                .append("# Regulatory Context\n\n")
                .append("Below are excerpts from official regulatory documents retrieved by semantic search. ")
                .append("Each excerpt is tagged with its source.\n\n");
        appendChunks(sb, chunks);

        sb.append("# Policy Section Under Review\n\n")
                .append("Language: ").append(language == null || language.isBlank() ? "unknown" : language)
                .append("\n\n")
                .append(policyText)
                .append("\n\n")
                .append("# Your Task\n\n")
                .append("Identify potential compliance violations between the policy section and the regulatory excerpts above.\n\n")
                .append("Rules:\n")
                .append("- regulatoryText MUST be a verbatim quote from one of the excerpts. Do not translate or paraphrase.\n")
                .append("- Only cite excerpts that genuinely conflict with or constrain the policy text.\n")
                .append("- If no violations are found, return an empty violations array and overallRisk LOW.\n")
                .append("- Severity HIGH for clear regulatory breaches, MEDIUM for likely concerns, LOW for ambiguous cases.\n\n")
                .append("# Output Format\n\n")
                .append("Return ONLY a JSON object matching this exact schema. ")
                .append("No preamble, no explanation, no markdown code fences.\n\n")
                .append(JSON_SCHEMA);

        return sb.toString();
    }

    public String buildRetryPrompt(String basePrompt) {
        return basePrompt + "\n\n" + RETRY_INSTRUCTION;
    }

    private void appendChunks(StringBuilder sb, List<ScoredRegulatoryChunk> chunks) {
        int idx = 1;
        for (ScoredRegulatoryChunk scored : chunks) {
            RegulatoryChunk c = scored.chunk();
            sb.append("[Excerpt ").append(idx++).append("] ")
                    .append("Source: ").append(c.lawName() != null ? c.lawName() : "unknown");
            if (c.year() != null) {
                sb.append(", ").append(c.year());
            }
            sb.append(" | Language: ").append(c.language() != null ? c.language() : "?");
            if (c.sectionPath() != null && !c.sectionPath().isBlank()) {
                sb.append(" | Section: ").append(c.sectionPath());
            }
            sb.append(" | Similarity: ").append(String.format(Locale.ROOT, "%.2f", scored.relevanceScore()))
                    .append("\n\n")
                    .append(c.originalText())
                    .append("\n\n---\n\n");
        }
    }
}