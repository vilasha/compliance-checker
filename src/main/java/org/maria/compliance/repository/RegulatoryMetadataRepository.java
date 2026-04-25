package org.maria.compliance.repository;

import org.maria.compliance.model.RegulatoryMetadata;

import java.util.List;

public interface RegulatoryMetadataRepository {

    long countByLawNameAndYear(String lawName, int year);

    List<RegulatoryMetadata> findAll();

    int deleteByLawNameAndYear(String lawName, int year);
}