package org.maria.compliance.service;

import org.maria.compliance.model.ComplianceAnalysisResult;
import org.maria.compliance.model.LlmAnalysisResponse;
import org.maria.compliance.model.ScoredRegulatoryChunk;

import java.util.List;
import java.util.Optional;

/**
 * Single-Query RAG Orchestration Service
 * <p>
 * One embedding pass, one vector search, one LLM synthesis call. This is the MVP
 * baseline against which the Multi-Query variant (ADR-002, Step 6) will be compared
 */
public interface SingleQueryRagService {

    /**
     * Full single-query RAG flow. Always returns a result; never throws. Parse failures
     * trigger configurable retries; if all attempts fail, a fallback result is returned
     */
    ComplianceAnalysisResult analyze(String policyText, String language);

    /**
     * Embed the policy text and retrieve top-K regulatory chunks above the similarity threshold
     * Applies language filter when language is non-blank
     */
    List<ScoredRegulatoryChunk> retrieveContext(String policyText, String language, int topK);

    /**
     * Send a prompt to the configured chat model and wrap the raw response
     */
    LlmAnalysisResponse callLlm(String prompt);

    /**
     * Attempt to parse the LLM response into a structured result.
     * Returns {@code Optional.empty()} on any parse or extraction failure so the
     * caller can decide whether to retry
     */
    Optional<ComplianceAnalysisResult> tryParseResponse(LlmAnalysisResponse raw, String policyText, long elapsedMs);
}