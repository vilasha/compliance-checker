package org.maria.compliance.service;

import org.maria.compliance.model.*;

import java.util.List;
import java.util.Map;

/**
 * Multi-Query RAG Orchestration Service
 * <p>
 * Implements enhanced retrieval strategy with multiple query perspectives
 * and result synthesis for richer compliance analysis.
 * <p>
 * This is a design outline - full implementation comes in Step 9.
 */
public interface MultiQueryRagService {

    /**
     * Main entry point: Analyze a policy section using multi-query retrieval
     *
     * @param policyText The policy section text to analyze
     * @param language   Language of the policy (de, fr, it, en)
     * @return ComplianceAnalysisResult with perspective breakdown
     */
    ComplianceAnalysisResult analyzeWithMultiQuery(String policyText, String language);

    /**
     * Step 1: Generate multiple query variants from different perspectives
     * <p>
     * Uses the LLM to create analytical queries representing:
     * - Strict legal interpretation
     * - Industry standard practice
     * - Edge cases and exceptions
     * - Cross-regulatory context
     * - Risk-based analysis
     *
     * @param policyText   The policy section to analyze
     * @param perspectives List of perspective configurations
     * @return List of QueryVariant objects, one per perspective
     */
    List<QueryVariant> generateQueryVariants(String policyText, List<PerspectiveConfig> perspectives);

    /**
     * Step 2: Execute parallel retrieval for all query variants
     * <p>
     * For each query variant:
     * - Generate embedding via Ollama (bge-m3)
     * - Perform vector similarity search in PGVector
     * - Return top-k regulatory chunks
     *
     * @param queryVariants List of query variants to retrieve for
     * @return Map of perspective name -> list of regulatory chunks
     */
    Map<String, List<RegulatoryChunk>> executeParallelRetrieval(List<QueryVariant> queryVariants);

    /**
     * Step 3: Aggregate and deduplicate chunks across all variants
     * <p>
     * - Deduplicate chunks using cosine similarity threshold
     * - Score chunks by frequency (how many variants retrieved them) + relevance
     * - Apply perspective weights from configuration
     * - Keep top-N unique chunks
     *
     * @param retrievalResults   Map of perspective -> chunks
     * @param perspectiveWeights Weight multipliers for each perspective
     * @return Aggregated and ranked list of unique regulatory chunks
     */
    List<ScoredRegulatoryChunk> aggregateAndDeduplicate(
            Map<String, List<RegulatoryChunk>> retrievalResults,
            Map<String, Double> perspectiveWeights
    );

    /**
     * Step 4: Build synthesis prompt with all perspectives and chunks
     * <p>
     * Constructs a comprehensive prompt that includes:
     * - Original policy text
     * - All query perspectives (what to look for)
     * - Aggregated regulatory chunks (context)
     * - Instructions to analyze from each angle
     *
     * @param policyText       Original policy text
     * @param queryVariants    Query variants with perspectives
     * @param aggregatedChunks Deduplicated regulatory chunks
     * @return Formatted prompt for LLM
     */
    String buildSynthesisPrompt(
            String policyText,
            List<QueryVariant> queryVariants,
            List<ScoredRegulatoryChunk> aggregatedChunks
    );

    /**
     * Step 5: Send synthesis prompt to fine-tuned LLM
     * <p>
     * Calls Ollama with the fine-tuned llama3.2:1b model
     * Model is trained to analyze from multiple perspectives
     * and produce structured violation reports
     *
     * @param synthesisPrompt The complete prompt
     * @return Structured LLM response with perspective-based violations
     */
    LlmAnalysisResponse callLlmForSynthesis(String synthesisPrompt);

    /**
     * Step 6: Parse LLM response into structured result
     * <p>
     * Extracts violations grouped by perspective:
     * - Which perspective identified which issue
     * - Severity levels
     * - Exact regulatory citations
     * - Recommendations
     *
     * @param llmResponse Raw LLM output
     * @return Structured ComplianceAnalysisResult
     */
    ComplianceAnalysisResult parseAndStructureResult(LlmAnalysisResponse llmResponse);
}