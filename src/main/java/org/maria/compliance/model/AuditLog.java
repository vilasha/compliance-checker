package com.insurance.compliance.model;

import java.time.LocalDateTime;

/**
 * Represents an audit log entry for compliance checking requests.
 * Tracks who uploaded what, when, and the outcome.
 */
public class AuditLog {

    private Long id;
    private LocalDateTime timestamp;
    private String username;
    private String fileName;
    private Long fileSizeBytes;
    private String processingStatus;  // SUCCESS, FAILURE, VALIDATION_ERROR
    private Integer violationCount;
    private String errorMessage;
    private Long processingTimeMs;
    private String sessionId;
    private String ipAddress;

    // Constructors
    public AuditLog() {
        this.timestamp = LocalDateTime.now();
    }

    public AuditLog(String username, String fileName, Long fileSizeBytes, String processingStatus) {
        this();
        this.username = username;
        this.fileName = fileName;
        this.fileSizeBytes = fileSizeBytes;
        this.processingStatus = processingStatus;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public Integer getViolationCount() {
        return violationCount;
    }

    public void setViolationCount(Integer violationCount) {
        this.violationCount = violationCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(Long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    @Override
    public String toString() {
        return "AuditLog{" +
                "id=" + id +
                ", timestamp=" + timestamp +
                ", username='" + username + '\'' +
                ", fileName='" + fileName + '\'' +
                ", processingStatus='" + processingStatus + '\'' +
                ", violationCount=" + violationCount +
                '}';
    }
}