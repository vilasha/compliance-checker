package org.maria.compliance.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

@Configuration
@Profile("!test")
public class EmbeddingStoreConfig {

    @Value("${compliance.embedding-store.table:regulatory_embeddings}")
    private String tableName;

    @Value("${compliance.embedding-store.dimension:1024}")
    private int dimension;

    @Value("${compliance.embedding-store.index-list-size:100}")
    private int indexListSize;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(DataSource dataSource) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(tableName)
                .dimension(dimension)
                .createTable(true)
                .useIndex(true)
                .indexListSize(indexListSize)
                .build();
    }
}