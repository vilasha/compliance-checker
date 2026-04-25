package org.maria.compliance.repository;

import org.maria.compliance.model.AuditLog;

import java.util.List;

public interface AuditLogRepository {

    Long save(AuditLog auditLog);

    List<AuditLog> findRecent(int limit);

    List<AuditLog> findByUsername(String username, int limit);
}