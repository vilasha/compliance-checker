# ADR-007: Solvency II — Re-adding Article and Chapter Heading Detection

## Status

Accepted — applied after a GitHub reviewer flagged that ADR-006's section-detection regex made all Solvency II / Solvency UK headings invisible.

## Context

ADR-006 documented an iteration of the section-detection regex motivated by reader feedback on Article 2 of the "Ship It with Java" newsletter. The fix removed `Art.`, `Artikel`, `Article`, `Section`, and `Chapter` from the heading-pattern alternation because all five appeared inline as cross-references far more often than as headings in FINMA documents.

A GitHub reviewer subsequently pointed out that this removal over-corrected for English-language regulatory corpora. Specifically, **Solvency II and Solvency UK PDFs structure their real headings as `Article N` and `Chapter N`** — for example:

- `Article 1 Subject matter`
- `Article 132 The prudent person principle`
- `Chapter VI Solvency capital requirement`

After ADR-006, none of these match the regex. `detectSections` falls back to the `Full Document` sentinel for any Solvency II document, and every chunk stored from such a document loses meaningful `section_path` metadata.

The reviewer also pointed out that the product **already exposes this code path**: `src/main/resources/templates/index.html:121` advertises "Provide a direct URL to a regulatory PDF document (e.g., from FINMA, Solvency II)". Anyone pasting a Solvency II URL into the ingestion textbox today would silently ingest a corpus with `section_path = "Full Document"` on every chunk. Not a hypothetical bug.

## The dilemma

ADR-006 removed `Article` and `Chapter` for a real reason: inline references like `Article 132 of the Directive` are extremely common in regulatory prose, and after PDF text wrap they land at line starts, where the old regex matched them as headings. The original bug was severe — documents shattered around inline cross-references with bogus `section_path` values.

Simply re-adding the markers would re-introduce that bug. We need a *stricter* pattern that distinguishes a heading's title from an inline reference's continuing prose.

## Decision

### 1. Re-add Article and Chapter detection with a Title-case-word filter

Append a third alternation to the existing pattern:

```
(?:Article|Chapter)[ \t]+(?:[IVXLCDM]+|[0-9]+(?:[a-z]+)?)[ \t]+\p{Lu}\p{Ll}+
```

Breakdown:

- `(?:Article|Chapter)` — the keyword, case-sensitive (EU directives capitalize these).
- `[ \t]+` — required horizontal whitespace.
- `(?:[IVXLCDM]+|[0-9]+(?:[a-z]+)?)` — Roman numeral (typical for Chapters) or Arabic number with optional letter suffix (`132a`, `132bis` — used after amendments).
- `[ \t]+` — required whitespace before the title.
- `\p{Lu}\p{Ll}+` — **the key filter**: a Title-case word (uppercase letter followed by at least one lowercase letter).

The Title-case-word requirement is the same conceptual trick used for numeric headings in ADR-006 (`\p{Lu}` after marker), calibrated for English. What follows the marker in real cases:

| Context | What follows | Filter result |
|---|---|---|
| Heading | `Article 132 The prudent person principle` (`T` then `he`) | Matches |
| Heading | `Chapter VI Solvency capital requirement` (`S` then `olvency`) | Matches |
| Inline ref | `Article 132 of the Directive` (`o` lowercase) | Rejected |
| Inline ref | `Article 132 applies to insurance` (`a` lowercase) | Rejected |
| Inline ref | `Article 132(1)(a)` (`(`) | Rejected |
| Inline ref | `Article 132, which states` (`,`) | Rejected |
| Inline ref | `as required by Article 132 of...` (`o` lowercase) | Rejected |
| Inline ref | `Article 132. The Member State` (`.`) | Rejected |

Inline references in regulatory prose are followed by prepositions, parentheses, or punctuation — never by a Title-case word. Heading titles are exactly Title-case noun phrases. The filter exploits this categorical difference.

### 2. Letter-suffix support for amended articles

`132a`, `132bis`, `132ter` — EU directives use these after amendments. The pattern accepts `[0-9]+(?:[a-z]+)?` to handle both `Article 132` and `Article 132bis Amended calculation method`. Costs nothing extra; preempts a future "why didn't it catch Article 132bis" issue.

### 3. Rejected alternative: corpus-specific patterns

The reviewer offered two paths: stricter heading-specific pattern *or* corpus-specific patterns selected by source. Corpus-specific was rejected for now because:

- The codebase has no concept of "corpus identity" at the section-detection layer — it would need a refactor to thread source metadata through `PdfProcessingServiceImpl#detectSections`.
- No Solvency II documents are currently in the corpus (it remains 200 chunks of FINMA). The cost of building per-corpus infrastructure for a hypothetical use case isn't justified.
- One stricter pattern works for both FINMA (already passing) and Solvency II (new positive cases) with no degradation.

If a future corpus genuinely cannot be expressed within a single shared pattern (e.g., a document family where heading and inline conventions overlap in ways the Title-case filter can't disambiguate), revisit. Until then, one pattern.

### 4. No re-ingestion needed

The current corpus is all FINMA. Behavior for those documents is unchanged — the new alternation only fires on text containing `Article` or `Chapter` at line start with a Title-case word following, which doesn't occur as a heading in FINMA's German structure.

The fix is forward-looking: anyone who pastes a Solvency II URL into the ingestion textbox after this change gets correct `section_path` metadata.

## Consequences

**Pros**

- Solvency II / Solvency UK / EU directive headings now produce meaningful `section_path` metadata, matching the product's stated capabilities.
- One regex covers both FINMA German and Solvency II English structures, sharing the same Title-case-word filter mechanism for inline-reference rejection.
- The pattern still doesn't match the genuine inline cross-references that ADR-006 fixed — verified across 8 distinct inline reference patterns.

**Cons**

- The pattern keeps growing — three alternations, two character classes, one negative lookahead, two regex extensions. At this point it sits close to the comfort ceiling for "regex in YAML." Another iteration may require moving the pattern to a Java constant or, eventually, replacing regex section detection with PDF-layout-aware or LLM-based segmentation.
- Theoretical edge case: an inline reference followed by a Title-case word (e.g., `Article 132 The Directive states that...` if it appeared inline). This construction is grammatically awkward in real regulatory prose — the directive name typically follows a preposition (`of the Directive`) — and is not observed in Solvency II texts. If it ever appears in a future corpus, it would cause a false positive.
- `Section`, `Title`, and `Annex` are still missing. Solvency II uses these as the structural layer *above* Chapters. They're also more common inline (`in accordance with Section 3...`, `as defined in Title I...`). If a future corpus needs them, they'd be added with the same Title-case-word filter.

**Test coverage added**

Four new tests in `PdfProcessingServiceTest`:

- `detectSections_findsArticleHeadings` — basic `Article N Title` detection
- `detectSections_findsChapterHeadingsWithRomanNumeral` — Roman-numeral chapter support
- `detectSections_findsArticleWithLetterSuffix` — `132a` and `132bis` suffix handling
- `detectSections_ignoresInlineArticleAndChapterReferences` — a single mixed text containing one real heading and **five distinct inline reference patterns** (`Article 101`, `Article 132(1)(a)`, `Chapter VI of Title I`, `Article 4, paragraph 2`, `Article 132 of the Directive`); only the real heading is detected.

All existing FINMA tests pass unchanged — no regressions.

## The narrative arc, made explicit

This ADR is iteration two of section detection. ADR-006 fixed the original "shattered documents" bug and removed inline-prone markers. ADR-007 re-adds two of those markers under a stricter constraint that survives the original failure mode. Two readers contributed; two ADRs document the iteration. The honest story to readers: heuristic section detection has a ceiling, and reaching it takes multiple cycles of catch → diagnose → fix → re-measure. Pattern is now near the ceiling of what's reasonable in a single regex.

## Related ADRs

- ADR-001: Overall architecture
- ADR-003: Data access layer (where `section_path` metadata is stored)
- ADR-005: Single-query RAG (consumes `section_path` for source citations)
- ADR-006: Section detection iteration 1 (the original bug fix; this ADR refines it)