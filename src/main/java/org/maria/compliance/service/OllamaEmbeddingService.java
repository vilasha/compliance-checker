package org.maria.compliance.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.RegulatoryChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
@Slf4j
@RequiredArgsConstructor
public class OllamaEmbeddingService implements EmbeddingService {

    private static final int BATCH_SIZE = 50;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Override
    public Embedding embedText(String text) {
        Response<Embedding> response = embeddingModel.embed(text);
        log.debug("Embedded text ({} chars) → {} dimensions, {} tokens",
                text.length(), response.content().dimension(), response.tokenUsage());
        return response.content();
    }

    @Override
    public String storeRegulatoryChunk(RegulatoryChunk chunk) {
        TextSegment segment = toTextSegment(chunk);
        Embedding embedding = embedText(chunk.originalText());
        String id = embeddingStore.add(embedding, segment);
        log.debug("Stored chunk id={} law={} section={}", id, chunk.lawName(), chunk.sectionPath());
        return id;
    }

    @Override
    public List<String> storeRegulatoryChunks(List<RegulatoryChunk> chunks) {
        List<String> allIds = new ArrayList<>();
        int totalBatches = (chunks.size() + BATCH_SIZE - 1) / BATCH_SIZE;

        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            int batchEnd = Math.min(i + BATCH_SIZE, chunks.size());
            List<RegulatoryChunk> batch = chunks.subList(i, batchEnd);
            int batchNumber = (i / BATCH_SIZE) + 1;

            log.info("Embedding batch {}/{} ({} chunks)", batchNumber, totalBatches, batch.size());

            List<TextSegment> segments = batch.stream()
                    .map(this::toTextSegment)
                    .toList();

            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            List<String> ids = embeddingStore.addAll(response.content(), segments);
            allIds.addAll(ids);

            log.info("Batch {}/{} stored ({} tokens used)",
                    batchNumber, totalBatches, response.tokenUsage());
        }

        log.info("Stored {} regulatory chunks total", allIds.size());
        return allIds;
    }

    @Override
    public EmbeddingSearchResult searchSimilar(String queryText, int maxResults, double minScore) {
        Embedding queryEmbedding = embedText(queryText);

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        return embeddingStore.search(request);
    }

    @Override
    public EmbeddingSearchResult searchSimilar(String queryText, int maxResults, double minScore,
                                               String language) {
        Embedding queryEmbedding = embedText(queryText);

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(metadataKey("language").isEqualTo(language))
                .build();

        return embeddingStore.search(request);
    }

    @Override
    public int dimension() {
        return embeddingModel.dimension();
    }

    private TextSegment toTextSegment(RegulatoryChunk chunk) {
        Metadata metadata = new Metadata();
        metadata.put("law_name", chunk.lawName());
        metadata.put("year", chunk.year());
        metadata.put("language", chunk.language());
        if (chunk.sectionPath() != null) {
            metadata.put("section_path", chunk.sectionPath());
        }
        if (chunk.sourceUrl() != null) {
            metadata.put("source_url", chunk.sourceUrl());
        }
        return TextSegment.from(chunk.originalText(), metadata);
    }
}