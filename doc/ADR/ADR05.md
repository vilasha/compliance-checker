# ADR-005: Single-Query RAG with JSON Parse Retries

## Status

Accepted — Step 4 (MVP).

## Context

Before the Multi-Query RAG flow (ADR-002, implemented in Step 6) can be evaluated, the project
needs a single-query baseline: one embedding pass, one vector search, one LLM call.

The baseline serves three purposes:

1. **Correctness floor** — proves the embedding store, the retrieval filter, and the
   prompt/parse pipeline all work end-to-end before adding multi-query complexity on top.
2. **Comparative anchor** — Step 6's multi-query implementation needs a baseline to
   measure latency overhead and analysis-quality lift against.
3. **Article material** — the contrast between single-query and multi-query is the
   narrative spine for the "Ship It with Java" newsletter entries covering RAG patterns.

## Decisions

### 1. New service: `SingleQueryRagService` and `SingleQueryRagServiceImpl`

A peer to `MultiQueryRagService`, deliberately named to make the comparison visible
in the file tree. Same return type (`ComplianceAnalysisResult`) so a future router or
A/B harness can swap implementations without touching callers.

### 2. Interface granularity

Four public methods:

- `analyze(policyText, language)` — full flow, always returns a result, never throws.
- `retrieveContext(policyText, language, topK)` — embed + vector search; returns `ScoredRegulatoryChunk`.
- `callLlm(prompt)` — one LLM round-trip wrapped as `LlmAnalysisResponse`.
- `tryParseResponse(raw, policyText, elapsedMs)` — returns `Optional` so retries don't need exception-as-control-flow.

Prompt construction is delegated to `CompliancePromptBuilder` (separate `@Component`)
so prompt iteration — the highest-leverage tuning surface in any RAG system — happens
without touching orchestration logic.

### 3. JSON parse retries on LLM output

Empirically, `llama3.1:8b` at `temperature 0.0` is mostly compliant with "respond with
only JSON" instructions, but not 100%. Failure modes seen during prompt prototyping:

- Wraps JSON in ` ```json ... ``` ` fences.
- Adds a one-line preamble ("Here is the analysis:") before the JSON.
- Emits trailing prose ("Let me know if you need clarification.").
- Rarely: malformed JSON (unescaped quote inside a string).

**Strategy:**

1. Extract JSON robustly — first try a fenced-block regex, then fall back to first `{` to last `}`.
2. Parse with Jackson, default-tolerant (`asText("")` for missing fields).
3. On any extraction or parse failure, retry up to `compliance.rag.parse-retry-max` times.
4. On retry, append a corrective instruction to the prompt:

   > Your previous response could not be parsed as JSON. Return ONLY the JSON object —
   > no preamble, no explanation, no markdown code fences.

   This matters because `temperature 0.0` is deterministic: re-sending the *same* prompt
   would return the *same* broken output. The corrective addendum changes the input, so
   the output differs.

5. After all retries are exhausted, return a fallback `ComplianceAnalysisResult` with
   `overallRisk=LOW`, empty violations, and a `recommendation` flagging the manual review.
   Never throw — the upstream SSE flow (Step 5) needs a result object to stream back.

**Configuration:**

```yaml
compliance:
  rag:
    parse-retry-max: 4   # 4 retries on top of the initial attempt = 5 max LLM calls per analysis
```

Default `4` is conservative. In practice the second attempt almost always succeeds once
the corrective instruction is appended. The cap protects against pathological loops
without truncating the long tail of recoverable cases.

### 4. Empty-retrieval short-circuit

If `retrieveContext` returns zero chunks above `similarity-threshold` (0.7), `analyze`
returns immediately with a "no regulatory context found" recommendation and skips the
LLM call. Saves ~10 seconds of LLM round-trip on inputs that have no semantic neighbours
in the corpus — common during early ingestion when the knowledge base is small.

### 5. Perspective tagging for violations

The `PerspectiveViolation.perspective` field is set to `Perspective.STRICT_LEGAL` for
all single-query results. The single-query approach is essentially a straight legal
reading of the retrieved chunks. The alternative — adding a `Perspective.SINGLE_QUERY`
enum value — would pollute the enum with a sentinel that's not really a perspective.

This means the UI (Step 8) can render single-query and multi-query results through the
same component without conditional logic.

### 6. Language handling

The system prompt is in English (the model reasons better in English regardless of corpus
language). The regulatory chunks are passed verbatim — the model is instructed to quote
them verbatim in `regulatoryText`, no translation. `violationDetail` and `recommendation`
come back in English.

When `language` on the request is non-blank, the embedding search applies the metadata
filter (`metadataKey("language").isEqualTo(language)`). When blank, the search is
unfiltered — useful for multi-language policies.

## Consequences

**Pros:**

- Working end-to-end compliance check, callable via `POST /api/analyze`, before Step 5
  adds upload/async/SSE complexity.
- Clear A/B comparison surface for Step 6.
- Retry-on-parse-failure handles the realistic noise in 8B-parameter local model output
  without compromising on structured-output requirements.

**Cons:**

- Up to 5 LLM round-trips per analysis in the worst case (~50 seconds at observed
  llama3.1:8b throughput). Mitigated in practice by the first retry succeeding ~95% of
  the time when initial parse fails.
- Single perspective means subtle violations that need cross-regulatory or risk-based
  framing will be missed until Step 6.

**Deferred to Step 12 (observability):**

- Token usage tracking (`LlmAnalysisResponse.tokensUsed` is hard-coded to 0 — the
  convenience `ChatModel.chat(String)` API discards usage metadata). Switching to
  `ChatRequest` will reclaim it.
- Histogram of how many attempts each analysis took — informs future tuning of
  `parse-retry-max`.

## Related ADRs

- ADR-001: Overall architecture
- ADR-002: Multi-Query Retrieval (the planned evolution beyond this baseline)
- ADR-003: Data access layer (JdbcClient + PgVectorEmbeddingStore split)