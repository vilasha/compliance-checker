package org.maria.compliance.service;

import org.maria.compliance.model.DocumentSource;
import org.maria.compliance.model.IngestionResult;

import java.util.List;

public interface DocumentIngestionService {

    IngestionResult ingestFromUrl(String pdfUrl);

    IngestionResult ingestFromUrl(DocumentSource source);

    List<IngestionResult> ingestBatch(List<DocumentSource> sources);
}