package org.maria.compliance.repository;

import org.maria.compliance.model.ProcessingStatus;
import org.maria.compliance.model.UserUpload;

import java.util.List;
import java.util.Optional;

public interface UserUploadRepository {

    Long save(UserUpload upload);

    Optional<UserUpload> findByTaskId(String taskId);

    int updateStatus(String taskId, ProcessingStatus status);

    int updateResult(String taskId, ProcessingStatus status, String resultJson);

    List<UserUpload> findByUsername(String username, int limit);
}