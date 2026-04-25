package org.maria.compliance.repository;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.AuditLog;
import org.maria.compliance.model.ProcessingStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Slf4j
public class JdbcAuditLogRepository implements AuditLogRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuditLogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Long save(AuditLog auditLog) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcClient.sql("""
                        INSERT INTO audit_log (timestamp, username, file_name, file_size_bytes,
                            processing_status, violation_count, error_message,
                            processing_time_ms, session_id, ip_address)
                        VALUES (COALESCE(:timestamp, CURRENT_TIMESTAMP), :username, :fileName, :fileSizeBytes,
                            :processingStatus, :violationCount, :errorMessage,
                            :processingTimeMs, :sessionId, :ipAddress)
                        """)
                .param("timestamp", auditLog.timestamp())
                .param("username", auditLog.username())
                .param("fileName", auditLog.fileName())
                .param("fileSizeBytes", auditLog.fileSizeBytes())
                .param("processingStatus", auditLog.processingStatus().name())
                .param("violationCount", auditLog.violationCount())
                .param("errorMessage", auditLog.errorMessage())
                .param("processingTimeMs", auditLog.processingTimeMs())
                .param("sessionId", auditLog.sessionId())
                .param("ipAddress", auditLog.ipAddress())
                .update(keyHolder, "id");

        Long id = keyHolder.getKeyAs(Long.class);
        log.debug("Saved audit log id={} user={} status={}", id, auditLog.username(), auditLog.processingStatus());
        return id;
    }

    @Override
    public List<AuditLog> findRecent(int limit) {
        return jdbcClient.sql("""
                        SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT :limit
                        """)
                .param("limit", limit)
                .query(auditLogRowMapper())
                .list();
    }

    @Override
    public List<AuditLog> findByUsername(String username, int limit) {
        return jdbcClient.sql("""
                        SELECT * FROM audit_log WHERE username = :username ORDER BY timestamp DESC LIMIT :limit
                        """)
                .param("username", username)
                .param("limit", limit)
                .query(auditLogRowMapper())
                .list();
    }

    private RowMapper<AuditLog> auditLogRowMapper() {
        return (rs, rowNum) -> AuditLog.builder()
                .id(rs.getLong("id"))
                .timestamp(rs.getTimestamp("timestamp").toLocalDateTime())
                .username(rs.getString("username"))
                .fileName(rs.getString("file_name"))
                .fileSizeBytes(rs.getLong("file_size_bytes"))
                .processingStatus(ProcessingStatus.valueOf(rs.getString("processing_status")))
                .violationCount(rs.getObject("violation_count", Integer.class))
                .errorMessage(rs.getString("error_message"))
                .processingTimeMs(rs.getObject("processing_time_ms", Long.class))
                .sessionId(rs.getString("session_id"))
                .ipAddress(rs.getString("ip_address"))
                .build();
    }
}