package org.maria.compliance.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

@Slf4j
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
        EmbeddingStore<TextSegment> store = PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(tableName)
                .dimension(dimension)
                .createTable(true)
                .useIndex(true)
                .indexListSize(indexListSize)
                .build();

        // Must run after build(): the builder owns table creation (createTable=true),
        // so this is the earliest point at which the table is guaranteed to exist
        ensureMetadataIndexes(dataSource);
        return store;
    }

    /**
     * JdbcRegulatoryMetadataRepository filters on JSON metadata keys
     * (source_url for ingest dedup, law_name/year/language for browsing)
     * Without expression indexes each of those is a sequential scan over every
     * embedding row — invisible at 122 documents, painful as the corpus grows.
     * Idempotent (IF NOT EXISTS) and scoped to the pool's default schema, same
     * as the table itself
     */
    private void ensureMetadataIndexes(DataSource dataSource) {
        List<String> statements = List.of(
                "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_meta_source_url ON " + tableName
                        + " ((metadata->>'source_url'))",
                "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_meta_law_name ON " + tableName
                        + " ((metadata->>'law_name'))",
                "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_meta_language ON " + tableName
                        + " ((metadata->>'language'))");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
            log.info("Metadata expression indexes ensured on {}", tableName);
        } catch (SQLException e) {
            // Non-fatal: queries stay correct without the indexes, just slower.
            log.warn("Could not create metadata indexes on {}: {}", tableName, e.getMessage());
        }
    }
}