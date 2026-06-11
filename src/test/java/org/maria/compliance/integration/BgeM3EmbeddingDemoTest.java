package org.maria.compliance.integration;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worked-example demonstration for the "Ship It with Java" newsletter article on
 * embeddings. Sends three sentences through the locally-running bge-m3 model and
 * prints the three pairwise cosine similarities, formatted for direct paste into
 * the article.
 *
 * <p>Requires Ollama running locally with the bge-m3 model pulled. Override the
 * base URL with the {@code OLLAMA_BASE_URL} environment variable if needed.
 *
 * <p>Run with:
 * <pre>
 *   mvn test -Dtest=BgeM3EmbeddingDemoTest
 * </pre>
 *
 * <p>Expected pattern: sentences 1 and 2 are paraphrases of the same AML idea, so
 * their cosine similarity should be high. Sentence 3 is about baking, so its
 * similarity to either of the regulatory sentences should be visibly lower.
 * bge-m3 produces enough contrast that the gap is clean even though
 * sentence-transformer style models tend to score "unrelated" pairs higher than
 * 0 — what matters is the gap, not the absolute number.
 */
@Disabled("turn on when needed. I wrote it to illustrate the case in an article")
class BgeM3EmbeddingDemoTest {

    private static final String OLLAMA_BASE_URL =
            System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");

    private static final String S1 =
            "The bank must report suspicious transactions to the regulator.";
    private static final String S2 =
            "Financial institutions are required to notify authorities about questionable transfers.";
    private static final String S3 =
            "The baker pulled a tray of fresh bread out of the oven.";

    @Test
    void bgeM3_groups_paraphrases_above_unrelated_sentences() {
        EmbeddingModel embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(OLLAMA_BASE_URL)
                .modelName("bge-m3")
                .timeout(Duration.ofSeconds(60))
                .build();

        Embedding e1 = embeddingModel.embed(S1).content();
        Embedding e2 = embeddingModel.embed(S2).content();
        Embedding e3 = embeddingModel.embed(S3).content();

        double sim12 = CosineSimilarity.between(e1, e2);
        double sim13 = CosineSimilarity.between(e1, e3);
        double sim23 = CosineSimilarity.between(e2, e3);

        printArticleBlock(sim12, sim13, sim23, e1.vector().length);

        // Generous thresholds — the article's point is the *contrast*, not the
        // absolute values, and we want this test to remain stable across minor
        // bge-m3 model revisions.
        assertThat(sim12)
                .as("S1↔S2 are paraphrases of the same AML idea; expect high similarity")
                .isGreaterThan(0.70);

        assertThat(sim13)
                .as("S1↔S3 are unrelated (regulation vs baking); should be lower than S1↔S2")
                .isLessThan(sim12);

        assertThat(sim23)
                .as("S2↔S3 are unrelated (regulation vs baking); should be lower than S1↔S2")
                .isLessThan(sim12);

        assertThat(sim12 - sim13)
                .as("Paraphrase-to-unrelated gap; bge-m3 should produce a clear separation")
                .isGreaterThan(0.15);
    }

    private void printArticleBlock(double sim12, double sim13, double sim23, int dimension) {
        String block = String.format(Locale.ROOT, """

                =============== bge-m3 cosine similarities ===============
                  S1 ↔ S2 (AML paraphrases):           %.4f
                  S1 ↔ S3 (regulation vs baking):      %.4f
                  S2 ↔ S3 (regulation vs baking):      %.4f

                  Paraphrase gap (S1↔S2 minus S1↔S3):  %.4f

                  S1: %s
                  S2: %s
                  S3: %s

                  Model: bge-m3 via local Ollama
                  Embedding dimensions: %d
                ===========================================================
                """,
                sim12, sim13, sim23,
                sim12 - sim13,
                S1, S2, S3,
                dimension);
        System.out.println(block);
    }
}