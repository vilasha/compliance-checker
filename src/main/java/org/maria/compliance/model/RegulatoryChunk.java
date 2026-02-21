package com.insurance.compliance.model;

import java.time.LocalDateTime;

/**
 * Represents a chunk of regulatory document stored in the vector database.
 * Each chunk contains a text excerpt and its vector embedding for similarity search.
 */
public class RegulatoryChunk {

    private Long id;
    private String lawName;
    private Integer year;
    private String language;
    private String sectionPath;
    private String originalText;
    private String sourceUrl;
    private float[] embedding;  // Vector embedding (1024 dimensions for bge-m3)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructors
    public RegulatoryChunk() {
    }

    public RegulatoryChunk(String lawName, Integer year, String language,
                           String sectionPath, String originalText, String sourceUrl) {
        this.lawName = lawName;
        this.year = year;
        this.language = language;
        this.sectionPath = sectionPath;
        this.originalText = originalText;
        this.sourceUrl = sourceUrl;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLawName() {
        return lawName;
    }

    public void setLawName(String lawName) {
        this.lawName = lawName;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getSectionPath() {
        return sectionPath;
    }

    public void setSectionPath(String sectionPath) {
        this.sectionPath = sectionPath;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "RegulatoryChunk{" +
                "id=" + id +
                ", lawName='" + lawName + '\'' +
                ", year=" + year +
                ", language='" + language + '\'' +
                ", sectionPath='" + sectionPath + '\'' +
                ", sourceUrl='" + sourceUrl + '\'' +
                '}';
    }
}