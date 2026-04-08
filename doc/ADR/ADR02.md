# ADR ENHANCEMENT: Multi-Query Retrieval with Perspective Synthesis

## Enhancement Overview

Instead of a single retrieval pass, the system will use **multi-query retrieval** with different interpretive perspectives, then synthesize results for richer compliance analysis.

## Query Variant Strategy

For each policy section, the system generates **3-5 query variants** representing different analytical perspectives:

1. **Strict Legal Reading** - Literal interpretation focusing on exact regulatory language
    - Example: "Does this policy clause literally violate Article 132 of Solvency II regarding minimum capital requirements?"

2. **Common Industry Interpretation** - How the regulation is typically understood in practice
    - Example: "How does industry standard practice interpret Article 132 for similar insurance products?"

3. **Edge Cases & Exceptions** - Boundary conditions and special circumstances
    - Example: "What exceptions or special provisions apply to Article 132 for this type of policy?"

4. **Cross-Regulatory Context** - Related regulations from other frameworks
    - Example: "How does this compare to FINMA's equivalent capital requirements?"

5. **Risk-Based Analysis** - Focus on underlying risk principles
    - Example: "What risk management principles does Article 132 aim to enforce, and does this policy satisfy them?"

## Implementation Flow

```
Policy Section (text)
        ↓
┌───────────────────────────────────────┐
│  Query Variant Generator              │
│  (Uses LLM to create 3-5 perspectives)│
└───────────────────────────────────────┘
        ↓
┌───────────────────────────────────────┐
│  Parallel Retrieval (for each variant)│
│  - Embed query variant                │
│  - Vector search PGVector (top-k=5)   │
│  - Each variant gets own chunk set    │
└───────────────────────────────────────┘
        ↓
┌───────────────────────────────────────┐
│  Result Aggregation                   │
│  - Deduplicate chunks across variants │
│  - Score by frequency + relevance     │
│  - Keep top-N unique chunks           │
└───────────────────────────────────────┘
        ↓
┌───────────────────────────────────────┐
│  Synthesis Prompt Construction        │
│  - Include all query perspectives     │
│  - Include aggregated regulatory chunks│
│  - Ask LLM to analyze from each angle │
└───────────────────────────────────────┘
        ↓
┌───────────────────────────────────────┐
│  LLM Analysis (Fine-tuned llama3.2:1b)│
│  - Considers multiple perspectives    │
│  - Produces nuanced violation report  │
│  - Cites exact regulatory text        │
└───────────────────────────────────────┘
        ↓
    Violation Report
    (with perspective breakdown)
```

## Benefits

1. **Richer Context**: Multiple perspectives catch violations that single-query might miss
2. **Nuanced Analysis**: Distinguishes between strict violations vs. industry practice vs. edge cases
3. **Better Coverage**: Cross-regulatory insights provide broader compliance picture
4. **Explainability**: Shows which perspective identified which issue

## Configuration

```yaml
compliance:
  rag:
    multi-query:
      enabled: true
      query-variants: 5  # Number of perspectives to generate
      perspectives:
        - name: strict_legal
          weight: 1.5      # Higher weight = more important
        - name: industry_standard
          weight: 1.2
        - name: edge_cases
          weight: 1.0
        - name: cross_regulatory
          weight: 1.1
        - name: risk_based
          weight: 1.3
      
      aggregation:
        deduplication-threshold: 0.9  # Cosine similarity for dedup
        max-chunks-per-variant: 5
        total-max-chunks: 15          # After aggregation
```

## Example Output Structure

```json
{
  "policySection": "Section 4.2: Capital Requirements",
  "violations": [
    {
      "perspective": "strict_legal",
      "severity": "HIGH",
      "regulatoryText": "Article 132: The Solvency Capital Requirement shall be...",
      "violationDetail": "Policy explicitly sets minimum capital below regulatory threshold",
      "source": "Solvency II Article 132"
    },
    {
      "perspective": "industry_standard",
      "severity": "MEDIUM",
      "regulatoryText": "FINMA Circular 2017/3 recommends...",
      "violationDetail": "While not strictly violating the letter, deviates from standard practice",
      "source": "FINMA Circular 2017/3"
    },
    {
      "perspective": "edge_cases",
      "severity": "LOW",
      "regulatoryText": "Solvency II Article 132(4) allows reduced capital if...",
      "violationDetail": "May qualify for exception under Article 132(4) but documentation unclear",
      "source": "Solvency II Article 132(4)"
    }
  ],
  "overallRisk": "HIGH",
  "recommendation": "Revise Section 4.2 to meet strict legal requirements under Article 132"
}
```

## Trade-offs

**Pros:**
- Much richer, more comprehensive analysis
- Catches subtle violations single-query misses
- Provides context (strict vs. practice vs. edge case)

**Cons:**
- 3-5x more vector searches (higher latency)
- More LLM tokens for query generation + synthesis
- Increased complexity in prompt engineering

**Mitigation:**
- Parallel retrieval keeps latency reasonable
- Caching of query variants for similar sections
- Configurable (can disable for simple checks)

## Performance Impact

- **Single-query approach**: ~2-3 seconds per policy section
- **Multi-query approach**: ~5-7 seconds per policy section
    - Query generation: +1s
    - Parallel retrieval: +2s (5x searches in parallel)
    - Synthesis: +1s (larger prompt)

Still acceptable for interactive use with SSE progress updates.