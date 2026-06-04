# ADR-008: Async Upload Pipeline with SSE Progress Streaming

## Status

Accepted — Step 5.

## Context

Step 4 produced a working synchronous `POST /api/analyze` endpoint that takes raw policy text and returns a `ComplianceAnalysisResult` blocking until the full RAG pipeline completes. That endpoint is useful for testing the prompt + retrieval logic with curl, but it cannot drive the upload UI: a real policy PDF runs through extraction, section detection, and N×LLM calls (one per section), each of which can include parse retries. Total time scales linearly with section count and easily runs to minutes for a multi-page document. A browser fetch blocking that long produces no feedback and trips upstream proxy timeouts.

The UI also already exposed an upload form whose submit handler was a placeholder alert. Step 5 wires it up properly.

## Decisions

### 1. Two-endpoint shape: `POST /api/upload` returns taskId; `GET /api/status/{taskId}` streams progress over SSE

The split is the standard pattern for long-running operations:

- **`POST /api/upload`** accepts `multipart/form-data` (file + language), validates synchronously (size, content type, non-empty), persists a `UserUpload` row, publishes an initial `UPLOADED` event, kicks off `@Async` processing, and returns `{ "taskId": "<uuid>" }` with `HTTP 202 Accepted`. The endpoint returns in milliseconds.
- **`GET /api/status/{taskId}`** opens a Server-Sent Events stream. The browser uses `EventSource`, which automatically reconnects on transient network failures and carries the session cookie for authentication.

### 2. In-memory `TaskEventBus` with replay-on-late-subscribe

Spring offers `SseEmitter` as the per-connection primitive but nothing for fan-out or for delivering missed events to clients that subscribe after the event already fired. `TaskEventBus` fills both gaps:

- `ConcurrentHashMap<String, List<TaskEvent>> history` — every event ever published for a task. Keyed by taskId.
- `ConcurrentHashMap<String, List<SseEmitter>> emitters` — currently-live subscribers per task.
- `publish(event)` appends to history and broadcasts to every live emitter. If the event is terminal (`COMPLETED` / `FAILED`), all emitters are completed after delivery.
- `subscribe(taskId, emitter)` registers the emitter, then **replays the full history** to it before returning. If the last event was terminal, the emitter is completed right after replay.

Replay-on-subscribe is what makes the system robust to the realistic case where the client subscribes to `/api/status` a few hundred milliseconds after `POST /api/upload` returns — by then the orchestrator may already have published `UPLOADED` and `EXTRACTING`. Without replay the client would miss those and only see events from the moment of subscription onward.

### 3. Database fallback for "no in-memory history"

If a client connects to `/api/status/{taskId}` and the bus has no history (e.g., the server restarted between upload and subscription, or memory was reclaimed), the `StatusController` synthesizes a terminal event from the persisted `UserUpload`:

- DB status `COMPLETED` → deserialize `result_json` to `PolicyAnalysisReport`, emit one `COMPLETED` event, close.
- DB status `FAILED` / `VALIDATION_ERROR` → emit one `FAILED` event with the persisted error, close.
- DB status `UPLOADING` / `PROCESSING` but no in-memory history → emit one `UPLOADED` event with a "restored from persisted state" message. Honest about the limitation: we know the task exists but we lost the live progress stream.

This means a user can refresh the page (or come back hours later) and still see the final result, as long as the upload was persisted.

### 4. Section-by-section sequential analysis

`PdfProcessingService.detectSections` (now trustworthy after ADR-006 and ADR-007) splits the policy PDF into sections; the orchestrator loops through them, calling `SingleQueryRagService.analyze` for each. After each section completes, a `SECTION_ANALYZED` event carries that section's `ComplianceAnalysisResult` plus progress counters (`sectionsCompleted` / `sectionsTotal`).

The loop is sequential because Ollama's local default configuration handles requests one at a time. Parallelizing across sections would require either a multi-threaded Ollama runtime or moving to the Azure OpenAI path (Step 11). Sequential is a known throughput limit — see consequences.

### 5. Aggregate report at the end

The orchestrator wraps the list of per-section results into a `PolicyAnalysisReport` with:

- `aggregateRisk` — the most severe `OverallRisk` across all sections. Note: the existing `OverallRisk` enum is declared `HIGH, MEDIUM, LOW`, so `HIGH.ordinal() = 0` (lower ordinal = higher severity). The aggregation comparison uses `<` not `>`. This is documented inline; future contributors will read the comment before rewriting the comparison.
- `totalViolations` — summed across sections.
- `totalProcessingTimeMs` — wall-clock time from start of async processing to completion.

The report is JSON-serialized and stored in `user_uploads.result_json` so a late subscriber can recover it. It is also delivered in the `COMPLETED` event.

### 6. SSE timeout: 10 minutes

`SseEmitter` constructed with `10 * 60 * 1000` ms. Long enough for a 30-section PDF analysed sequentially at ~10–20 s per section including parse retries. If the analysis legitimately takes longer, the stream times out gracefully and the client can re-`GET` `/api/status/{taskId}` to pick up the final state via the DB fallback (decision 3).

### 7. Authentication via existing form-login session

The existing `SecurityConfig` uses form-based login. `EventSource` automatically sends same-origin cookies, so the session that authenticates the page authorizes the SSE subscription too. No new auth code. CSRF is already disabled for `/api/**` (existing config), so the multipart POST works without a token.

### 8. Reading bytes synchronously before the async hand-off

`MultipartFile` is request-scoped — by the time the `@Async` method runs on a different thread, the request lifecycle may have ended and the file handle is gone. The orchestrator reads `file.getBytes()` in the synchronous `submit()` method and passes the `byte[]` to the async method along with the filename. Trade: doubles the memory footprint of the upload temporarily; not significant for the 10 MB cap, would matter if the cap grew.

## Consequences

**Pros**

- End-to-end flow now works: drag a PDF onto the page, watch live progress, see per-section results with severity badges and source citations.
- No throwaway code — the sync `/api/analyze` endpoint from Step 4 is retained for raw-text testing; the new endpoints are the production shape.
- Robust to common UI patterns: refresh page mid-analysis (DB fallback), drop network connection briefly (`EventSource` auto-reconnects), navigate away and return (DB fallback again).
- `TaskEventBus` is a clean seam: publishing is just `bus.publish(event)` from anywhere in the orchestrator, so adding observability metrics or audit log entries (Step 7) hooks in at the bus level without changing the rest of the pipeline.

**Cons**

- **Single-instance only.** `TaskEventBus` stores history and emitters in process memory. A second instance of the app behind a load balancer would never see another instance's events. Multi-instance deployment requires a shared broker (Redis pub/sub for live fanout, plus the existing DB for terminal-state recovery). Out of scope until Step 11 (Azure deployment).
- **Memory grows unbounded for active tasks.** Event history is never evicted. Sessions are bounded in practice by the worker pool size (5 threads) and SSE timeout (10 minutes), so growth is small, but no explicit eviction policy is in place. A Caffeine cache with `expireAfterWrite` is the obvious upgrade if this becomes a problem.
- **Sequential section analysis is the throughput ceiling.** A 30-section PDF takes ~5 minutes minimum. Acceptable for the current use case (one user analyzing one document at a time), not for batch processing. Parallel-across-sections requires either upgrading Ollama config or switching to Azure OpenAI (Step 11).
- **No cancellation.** A user who realizes they uploaded the wrong PDF can't cancel mid-analysis from the UI. The async job runs to completion. Adding cancellation would require a `Map<taskId, Future>` or interruptible state polling — meaningful complexity for a marginal feature.

**Deferred**

- Audit log integration — the bus is the right place to hook the existing `JdbcAuditLogRepository` (Step 7).
- Distributed event bus for multi-instance deploys (Step 11).
- Parallel section analysis (Step 11 with Azure OpenAI).
- Eviction policy on event history (any time it becomes relevant).
- Cancellation (post-MVP if requested).

**Test coverage added**

- `TaskEventBusTest` — 9 cases covering publish/store, replay on late subscribe, terminal-event completion, multi-subscriber broadcast, task isolation, and post-completion publish safety.
- `AnalysisOrchestratorImplTest` — 7 cases covering submit returning taskId, validation rejections (empty / wrong content type / oversized), happy-path event sequence (`EXTRACTING` → `SECTIONS_DETECTED` → N × `SECTION_ANALYZED` → `COMPLETED`), per-section progress counters, aggregate risk escalation (LOW + HIGH → HIGH), and failure path emitting `FAILED` plus persisting `ProcessingStatus.FAILED`.

Mockito throughout, no Spring context, runs in milliseconds. Integration tests against a real Ollama + Postgres are Step 9.

## Amendments — Post-ship iterations

The Step 5 pipeline was deployed against a real Ollama + Postgres setup right after the initial shipping. Three classes of issues surfaced quickly; each one was a lesson the original design didn't anticipate. Documented here rather than in fresh ADRs because they're all refinements of the same subsystem — readers should be able to follow the iteration in one place. Amendments are lettered to distinguish them from the original numbered decisions they revise.

### A. `@Async` self-invocation bug (revises decision 1)

**Problem.** The first real upload hung. The browser's `POST /api/upload` blocked for minutes instead of returning the taskId in milliseconds. The Ollama timeout eventually surfaced, but mid-stream inside the upload request, not as an event on the SSE channel.

**Root cause.** `AnalysisOrchestratorImpl.submit()` called `processAsync()` directly on `this`. Spring's `@Async` only fires when the annotated method is invoked **through the AOP proxy**. Same-class self-invocation bypasses the proxy entirely — the call runs synchronously on the calling thread. The HTTP request thread did all the work.

**Stack-trace fingerprint.** A proxy frame existed for `submit` (`AnalysisOrchestratorImpl$$SpringCGLIB$$0.submit`) but no equivalent for `processAsync` — it was invoked directly on the bare object. Once `@Async` is firing correctly the worker frames appear under `AsyncExecutionInterceptor.lambda$invoke$0` and `ThreadPoolExecutor.runWorker`.

**Fix.** Extract the async work to a separate Spring bean — `AsyncAnalysisWorker` — with `@Async("taskExecutor")` on its single `run()` method. The orchestrator now does validation, persistence, the initial `UPLOADED` event, and delegates to `worker.run(...)`. Cross-bean calls go through the proxy → `@Async` dispatches → upload returns in milliseconds.

**Lesson for the test suite.** The original `AnalysisOrchestratorImplTest` passed before the fix because it invoked `orchestrator.processAsync(...)` from a test, which also bypasses the proxy. Mockito-level unit tests can verify a method *body* but cannot prove `@Async` is wired correctly — that's an integration-test concern. To catch this kind of bug at the unit level you'd need an `@SpringBootTest` that asserts `submit()` returns in milliseconds, or one that captures which thread ran the worker. Both are Step 9 work.

### B. Timeout calibration for CPU-only Ollama (revises decision 6; adds two new knobs)

**Problem.** Even after fix A, the first run timed out. The per-section LLM call hit the JDK HTTP client's request timeout on slow hardware.

**Three changes**:

1. **`compliance.ollama.timeout` raised** from 120s to 5m, then to 30m for development on the slowest local hardware. `llama3.1:8b` on CPU with a real prompt — system instructions, retrieved chunks, policy section, JSON schema — routinely needs 60–180 s per call. The original 120 s left no safety margin. On GPU this can be tightened back.
2. **SSE timeout made configurable** via `compliance.processing.sse-timeout`, default raised from a hardcoded 10 minutes to 1h. The arithmetic: worst-case wall time = `sections × (ollama.timeout × (1 + max-retries))`. With 13 sections, 30 min Ollama timeout, and 3 retries, the worst case is `13 × 30 × 4 = 26` hours — far beyond any reasonable SSE timeout. The 1h default covers the realistic case (a few sections hitting retries); `EventSource` auto-reconnects past it and the `StatusController` replays history from the bus, so a too-short value wastes reconnects rather than losing data.
3. **Temporary chat model switch to `qwen2.5:3b`.** As a dev-time workaround for slow CPU-only Ollama, the default `compliance.ollama.chat-model` is now `qwen2.5:3b` rather than `llama3.1:8b`. The smaller model runs 3–5× faster on CPU at the cost of:
    - Less nuanced reasoning, so subtle compliance conflicts may be missed.
    - Higher rate of JSON parse retries (smaller models are less reliable at strict structured output).
    - Quality is sufficient for end-to-end pipeline testing and UI screenshots, but not for the LinkedIn-grade demos this build series aspires to.

   The path back: revert to `llama3.1:8b` on a GPU-capable machine, or move to Azure OpenAI in Step 11 (which is where production should run anyway). Marked TEMPORARY in the YAML comment to keep the intent obvious to whoever reads the config later.

### C. Operator-facing visibility for slow operations (extends decision 4)

**Problem.** With the longer per-call timeouts in amendment B, a single section can take many minutes. Between `SECTIONS_DETECTED` and the first `SECTION_ANALYZED` event, the UI sat frozen on "Detected N section(s) to analyze" with no indication anything was happening. Users couldn't distinguish a long-running analysis from a crashed backend.

A related issue: when `SingleQueryRagServiceImpl.analyze()` catches a timeout exception internally and returns a fallback `ComplianceAnalysisResult` (with `recommendation` like "Compliance analysis failed: TimeoutException — …"), the worker emits `SECTION_ANALYZED` carrying that result. The UI rendered it identically to a successful clean analysis: green badge, "no violations detected". Section failures were invisible to the user.

**Three additions**:

1. **`SECTION_STARTED` event** added to `TaskEventType` and emitted before each section's LLM call. Carries `sectionsTotal` and `sectionsCompleted = i` (zero-indexed). The UI shows "Section N of M — Analyzing: *heading*" with the progress bar advancing as soon as the worker picks up each section.
2. **Client-side stall watchdog.** The browser tracks time since the last event. After 2 minutes of silence, shows a yellow warning ("No update from the backend in 2 minutes. The LLM may be slow or unreachable."). After 45 minutes of total silence, hard-fails the UI with an error message and closes the `EventSource`. The watchdog is reset on every incoming event and on `onopen` (which fires on auto-reconnect). This is the answer to "the UI lied about success" — it now stops pretending everything is fine when nothing is happening.
3. **Per-section failure styling.** The result-card renderer now calls `classifySectionStatus()`, which pattern-matches the `recommendation` field for the three soft-failure modes that `SingleQueryRagServiceImpl` produces:
    - `"Compliance analysis failed: …"` → red "Failed" badge, danger border.
    - `"LLM response could not be parsed…"` → yellow "Unparsed" badge, warning border.
    - `"No relevant regulatory context…"` → grey "No context" badge.
    - anything else → the existing HIGH/MEDIUM/LOW severity badge.

   Pattern-matching the recommendation string is pragmatic and self-contained. The structurally better fix is an explicit `status: SectionStatus` field on `ComplianceAnalysisResult`, which is a small model change worth a follow-up.

### Test coverage post-amendments

The `AnalysisOrchestratorImpl` / `AsyncAnalysisWorker` refactor split the original 7 orchestrator tests across two classes:

- `AnalysisOrchestratorImplTest` — 6 cases. Submit-level only: validation rejections (empty / wrong content type / oversized), taskId generation, `UserUpload` persistence, `UPLOADED` event publish, worker delegation. The worker is mocked.
- `AsyncAnalysisWorkerTest` — 3 cases. Section-by-section processing: happy-path event sequence including `SECTION_STARTED` for every section, aggregate risk escalation (LOW + HIGH → HIGH), failure-path emitting `FAILED` plus persisting `ProcessingStatus.FAILED`.

Total Step 5 coverage: `TaskEventBusTest` (9) + `AnalysisOrchestratorImplTest` (6) + `AsyncAnalysisWorkerTest` (3) = 18 unit tests. Still Mockito-only, no Spring context. The `@Async`-correctness gap that hid amendment A is still uncovered at the unit level — Step 9 work.

## Related ADRs

- ADR-001: Overall architecture
- ADR-005: Single-query RAG (the per-section analyzer this orchestrator drives)
- ADR-006 / ADR-007: Section detection (the section boundaries the orchestrator iterates over)