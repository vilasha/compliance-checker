package org.maria.compliance.service;

import org.maria.compliance.model.DocumentSource;

import java.util.List;

public interface FinmaScraperService {

    List<DocumentSource> discoverDocuments();
}