package org.maria.compliance.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.maria.compliance.model.RegulatoryChunk;

import java.util.List;

public interface EmbeddingService {

    Embedding embedText(String text);

    String storeRegulatoryChunk(RegulatoryChunk chunk);

    List<String> storeRegulatoryChunks(List<RegulatoryChunk> chunks);

    EmbeddingSearchResult<TextSegment> searchSimilar(String queryText, int maxResults, double minScore);

    EmbeddingSearchResult<TextSegment> searchSimilar(String queryText, int maxResults, double minScore, String language);

    int dimension();
}