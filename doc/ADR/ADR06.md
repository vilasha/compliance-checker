# ADR-006: Section Detection Regex Iteration

## Status

Accepted — applied after reader feedback on Article 2 of the "Ship It with Java" newsletter series, and refined after measurement on the re-ingested corpus.

## Context

PDF text from regulatory documents flows as a single stream after PDFBox extraction. To produce useful retrieval chunks — and to populate the `section_path` metadata that the LLM later cites as a source in `regulatoryText` (ADR-005) — the ingestion pipeline must split documents at heading boundaries before chunking.

Section detection in `PdfProcessingServiceImpl#detectSections` is regex-driven: a configurable `heading-patterns` value from `application.yml` is compiled with `Pattern.MULTILINE` and the matches define section boundaries. The original pattern was:

```
^(Art\.|§|Article|Section|Chapter|Artikel)
```

This pattern shipped through Step 3 (ingestion) and was used to ingest the first batch of 122 FINMA documents into PGVector. A reader of the second newsletter article pointed out that it doesn't work for FINMA documents.

## The bug

FINMA documents (and most Swiss regulatory texts) structure headings as Roman numerals with optional sub-numbering — `I. Allgemeine Ausführungen`, `II. Risikoanalyse`, `III.3.1 Einhaltung der Geldwäschereivorschriften`. None of these match the original pattern.

Worse, the pattern matched **every line beginning with** `Art.` / `Artikel` / `Article` / `Section` / `Chapter` / `§`. After PDF text wrap, regulatory prose constantly produces lines starting with inline cross-references like `Art. 6 Abs. 1 GwG schreibt vor...`. Each such reference was detected as a section boundary, shattering documents at the wrong points.

Smoke test against a realistic snippet with one real heading and three inline references:

| | Old regex | New regex |
|---|---|---|
| `I. Einleitung` (the actual heading) | missed | detected |
| `Art. 6 Abs. 1 GwG schreibt vor, dass Finanzintermediäre...` | matched as "heading" | ignored |
| `Artikel 4 der Solvency-II-Richtlinie definiert die Anforderungen.` | matched as "heading" | ignored |
| `Article 132 of the directive sets out the prudent person principle.` | matched as "heading" | ignored |

The `section_path` metadata stored for affected chunks was therefore wrong — meaning LLM-generated citations quoting that metadata would also be wrong.

## Decisions

### 1. New regex — anchored on structurally distinct markers

The shipped pattern (`application.yml`, single-quoted YAML, literal backslashes):

```
^[ \t]*(?![0-9]+\.[ \t]+(?:Januar|January|Februar|February|März|March|April|Mai|May|Juni|June|Juli|July|August|September|Oktober|October|November|Dezember|December)\b)(?:(?:[IVX]+|[0-9]+)\.(?:[0-9]+(?:\.[0-9]+)*\.?)?[ \t]+\p{Lu}|§[ \t]*[0-9]+)
```

Decomposed:

- `^[ \t]*` — line start, optionally indented. Horizontal whitespace only; `\s` would consume newlines and produce empty matches.
- `(?!\d+\.[ \t]+(?:Januar|January|...)\b)` — negative lookahead that rejects calendar dates (see decision 3).
- Two alternations follow:
    - `(?:[IVX]+|[0-9]+)\.(?:[0-9]+(?:\.[0-9]+)*\.?)?[ \t]+\p{Lu}` — Roman or numeric marker, optional sub-numbering, then horizontal whitespace and an uppercase letter.
    - `§[ \t]*[0-9]+` — paragraph sign with number.

Three deliberate changes from the original:

(a) **Dropped from the alternation**: `Art.`, `Artikel`, `Article`, `Section`, `Chapter`. These appear inline as cross-references far more often than as headings. Keeping them at line-start as the *sole* signal of heading-ness was the root cause of the bug.

(b) **Added Roman numeral markers** (`[IVX]+`, covers I through XXXIX — sufficient for any regulatory document we expect to ingest) **and numeric markers** (`[0-9]+`), each with optional sub-numbering (`III.3.1`, `3.1.4`). Both must be followed by horizontal whitespace and a Unicode uppercase letter (`\p{Lu}` — covers Ä Ö Ü and any non-Latin script). The uppercase requirement is the key filter: an inline numeric reference is followed by lowercase prose ("3.1 wird hier..."); a heading is followed by a capitalized title.

(c) **Negative lookahead for calendar dates** (decision 3).

`§ N` is kept as a marker because the paragraph sign is rarely used inline as a sentence-starting marker in FINMA documents. Not a guarantee — a future iteration may need to harden this — but no false positives observed in the re-ingested corpus.

### 2. Heading patterns remain configurable via YAML

The regex stays in `application.yml` rather than moving to a code constant. Different corpora may legitimately need different patterns (Solvency II English vs. Swiss German vs. EBA guidelines). The default in `application-test.yml` mirrors production so the test suite exercises the shipped logic.

### 3. Date exclusion via negative lookahead — added after measurement

After decision 1 was shipped and the corpus re-ingested, an inspection of the resulting 200 chunks across 57 unique `section_path` values surfaced two suspicious entries:

- `1. Januar des Folgejahres vorgenommen werden sollen, gilt der 31. Juli des`
- `30. April 2026 (s. Art. 25 Abs. 3 Versicherungsaufsichtsgesetz vom 17. Dezember`

Both are PDF text wrap producing dates at line starts. German months are capitalized nouns, so `1. Januar` satisfies "number + dot + horizontal whitespace + uppercase letter" — the exact pattern used for numeric headings.

The negative lookahead `(?!\d+\.[ \t]+(?:Januar|January|...)\b)` rejects these without affecting any real heading. The tradeoff: if a future regulatory document ever introduced a section literally titled "1. Januar Massnahmen", the lookahead would reject it. In FINMA's structure this case does not occur.

Both German and English month names are included to keep the pattern useful for the Solvency II / EBA documents that may be ingested later.

### 4. Migration: drop-and-re-ingest

The 122 already-ingested chunks had wrong `section_path` metadata. Rather than backfill via SQL (which would still need to re-detect sections), the corpus was dropped from `dev.regulatory_embeddings` via DBeaver and re-scraped from FINMA:

```sql
DELETE FROM dev.regulatory_embeddings;
```

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev,scrape
```

Re-ingestion produced **200 chunks across 57 unique section paths** — a ~3.5 chunks/section ratio that looks healthy (versus the old corpus where many "sections" were inline references). Verified absence of `Art./Artikel/Article` false positives across the new metadata.

The date-exclusion lookahead (decision 3) was added *after* the re-ingestion. The two affected chunks remain in the corpus with slightly mislabeled `section_path` values, but their chunk text is bounded correctly and they still retrieve normally. Not worth a third re-ingest for 2 of 200 chunks.

### 5. Residual issue accepted: multi-line heading truncation

Measurement on the re-ingested corpus surfaced 7/200 chunks (3.5%) where the section heading wraps across PDF lines. Examples:

- `III.3.4 Management der IKT-Risiken (Bewilligungsträger Fondsleitung` — closing paren on next line
- `III.3 Spezifische Vorgaben zu einzelnen Prüfgebieten bzw.` — ends with German connector "bzw."
- `I. Genehmigungspflichtige Elemente sowie`
- `III.3.7 Berechnung des Nettoinventarwertes und der Ausgabe- und`
- `V.1 Übereinstimmung zwischen Offenlegungsvorschlag und`

This is **not** a regex problem. The heading-extraction code reads exactly one line after the regex match:

```java
int lineEnd = text.indexOf('\n', matcher.start());
```

Fixing it requires continuation heuristics — detect that the next line is part of the same heading via signals like trailing connectors (und / sowie / oder / bzw.), unclosed parentheses, or short follow-on lines without their own marker. Each of those is dangerous: gluing two distinct sections together is worse than truncating a label.

Important property of the current truncation: only the *displayed* `section_path` label is truncated. Section content boundaries run from one match position to the next match position, not by lines, so chunk text itself is bounded correctly. Retrieval is unaffected; only LLM-generated citations would quote a truncated label.

**Decision**: defer the fix. Document the limit. The next iteration of section detection — whether continuation heuristics, PDF layout-tree consultation, or an LLM-based segmentation pass — is its own piece of work and likely its own newsletter article.

## Consequences

**Pros**

- 200 chunks carry meaningful `section_path` metadata. The LLM, prompted to cite sources, quotes real section headings instead of inline cross-references.
- Pattern generalizes beyond FINMA to any corpus using Roman or numeric section markers — covers most EU regulatory documents.
- Pattern lives in YAML; additional corpora (Solvency II, EBA, BaFin) can tune without code changes.
- Measurement step is now part of the section-detection workflow: drop-and-re-ingest, then sample `section_path` distribution before declaring success. Surfaced the date issue that pure code review would have missed.

**Cons**

- Won't catch `Section 5: Title` or `Article IV: Title` formatted headings if any future corpus uses these as the structural layer. FINMA doesn't.
- `§ N` could still false-positive on inline references that happen to wrap to a line start (`...gemäß § 12 Abs. 2...` → `§ 12 Abs. 2...`). Not seen in the current corpus but possible.
- Multi-line heading truncation (~3.5% of chunks) accepted as a known limit (decision 5).
- The regex has grown in complexity (negative lookahead, Unicode classes, alternation with sub-numbering). Each addition was justified by evidence, but the pattern is now near the ceiling of what's reasonable to keep in YAML. A further iteration will likely require moving to code.

**Deferred**

- Continuation-line heading reconstruction (the truncation issue in decision 5).
- LLM-based or PDF-layout-based section detection — would consult PDF structure tree or use a small model pass for segmentation. Out of scope for regex iteration.
- Per-corpus pattern overrides — currently one pattern fits all; might need to specialize by `law_name` or source for non-FINMA corpora.

**Test coverage**

Unit tests in `PdfProcessingServiceTest` cover:

- Roman numeral headings (single and sub-numbered)
- Numeric and sub-numbered headings
- `§ N` paragraph markers
- Rejection of inline `Art./Artikel/Article` references (the central bug fix)
- Rejection of lowercase content after the marker
- Rejection of German and English calendar dates at line start
- Fallback to "Full Document" when no headings detected

## Related ADRs

- ADR-001: Overall architecture (section detection sits in the ingestion pipeline)
- ADR-003: Data access layer (the `section_path` metadata key is stored in LangChain4j's JSONB metadata column)
- ADR-005: Single-query RAG (consumes `section_path` for source citations in `regulatoryText`)