package org.maria.compliance.repository;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.RegulatoryMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Slf4j
public class JdbcRegulatoryMetadataRepository implements RegulatoryMetadataRepository {

    private final JdbcClient jdbcClient;
    private final String tableName;

    public JdbcRegulatoryMetadataRepository(JdbcClient jdbcClient,
                                            @Value("${compliance.embedding-store.table:regulatory_embeddings}") String tableName) {
        this.jdbcClient = jdbcClient;
        this.tableName = tableName;
    }

    @Override
    public long countByLawNameAndYear(String lawName, int year) {
        Long count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM " + tableName +
                                " WHERE metadata->>'law_name' = :lawName AND (metadata->>'year')::int = :year")
                .param("lawName", lawName)
                .param("year", year)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    @Override
    public List<RegulatoryMetadata> findAll() {
        return jdbcClient.sql("""
                        SELECT metadata->>'law_name' AS law_name,
                               (metadata->>'year')::int AS year,
                               metadata->>'language' AS language,
                               COUNT(*) AS chunk_count
                        FROM """ + tableName + """
                         WHERE metadata->>'law_name' IS NOT NULL
                        GROUP BY metadata->>'law_name', metadata->>'year', metadata->>'language'
                        ORDER BY metadata->>'law_name', (metadata->>'year')::int DESC
                        """)
                .query((rs, rowNum) -> RegulatoryMetadata.builder()
                        .lawName(rs.getString("law_name"))
                        .year(rs.getInt("year"))
                        .language(rs.getString("language"))
                        .chunkCount(rs.getLong("chunk_count"))
                        .build())
                .list();
    }

    @Override
    public int deleteByLawNameAndYear(String lawName, int year) {
        int deleted = jdbcClient.sql(
                        "DELETE FROM " + tableName +
                                " WHERE metadata->>'law_name' = :lawName AND (metadata->>'year')::int = :year")
                .param("lawName", lawName)
                .param("year", year)
                .update();
        log.info("Deleted {} chunks for law={} year={}", deleted, lawName, year);
        return deleted;
    }
}