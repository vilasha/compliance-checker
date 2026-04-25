package org.maria.compliance.repository;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.ProcessingStatus;
import org.maria.compliance.model.UserUpload;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Slf4j
public class JdbcUserUploadRepository implements UserUploadRepository {

    private final JdbcClient jdbcClient;

    public JdbcUserUploadRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Long save(UserUpload upload) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcClient.sql("""
                        INSERT INTO user_uploads (task_id, username, file_name, file_size_bytes, status)
                        VALUES (:taskId, :username, :fileName, :fileSizeBytes, :status)
                        """)
                .param("taskId", upload.taskId())
                .param("username", upload.username())
                .param("fileName", upload.fileName())
                .param("fileSizeBytes", upload.fileSizeBytes())
                .param("status", upload.status().name())
                .update(keyHolder, "id");

        Long id = keyHolder.getKeyAs(Long.class);
        log.debug("Saved upload id={} taskId={} file={}", id, upload.taskId(), upload.fileName());
        return id;
    }

    @Override
    public Optional<UserUpload> findByTaskId(String taskId) {
        return jdbcClient.sql("SELECT * FROM user_uploads WHERE task_id = :taskId")
                .param("taskId", taskId)
                .query(uploadRowMapper())
                .optional();
    }

    @Override
    public int updateStatus(String taskId, ProcessingStatus status) {
        return jdbcClient.sql("UPDATE user_uploads SET status = :status WHERE task_id = :taskId")
                .param("status", status.name())
                .param("taskId", taskId)
                .update();
    }

    @Override
    public int updateResult(String taskId, ProcessingStatus status, String resultJson) {
        return jdbcClient.sql("""
                        UPDATE user_uploads SET status = :status, result_json = :resultJson WHERE task_id = :taskId
                        """)
                .param("status", status.name())
                .param("resultJson", resultJson)
                .param("taskId", taskId)
                .update();
    }

    @Override
    public List<UserUpload> findByUsername(String username, int limit) {
        return jdbcClient.sql("""
                        SELECT * FROM user_uploads WHERE username = :username
                        ORDER BY upload_timestamp DESC LIMIT :limit
                        """)
                .param("username", username)
                .param("limit", limit)
                .query(uploadRowMapper())
                .list();
    }

    private RowMapper<UserUpload> uploadRowMapper() {
        return (rs, rowNum) -> UserUpload.builder()
                .id(rs.getLong("id"))
                .taskId(rs.getString("task_id"))
                .username(rs.getString("username"))
                .fileName(rs.getString("file_name"))
                .fileSizeBytes(rs.getLong("file_size_bytes"))
                .uploadTimestamp(rs.getTimestamp("upload_timestamp").toLocalDateTime())
                .status(ProcessingStatus.valueOf(rs.getString("status")))
                .resultJson(rs.getString("result_json"))
                .build();
    }
}