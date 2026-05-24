package org.maria.compliance.repository;

import org.maria.compliance.model.RegulatoryMetadata;

import java.util.List;

public interface RegulatoryMetadataRepository {

    long countByLawNameAndYear(String lawName, int year);

    boolean existsBySourceUrl(String sourceUrl);

    List<RegulatoryMetadata> findAll();

    int deleteByLawNameAndYear(String lawName, int year);
}