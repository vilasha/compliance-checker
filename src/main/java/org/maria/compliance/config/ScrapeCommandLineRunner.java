package org.maria.compliance.config;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.DocumentSource;
import org.maria.compliance.model.IngestionResult;
import org.maria.compliance.service.DocumentIngestionService;
import org.maria.compliance.service.FinmaScraperService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("scrape")
@Slf4j
public class ScrapeCommandLineRunner implements CommandLineRunner {

    private final FinmaScraperService scraperService;
    private final DocumentIngestionService ingestionService;

    public ScrapeCommandLineRunner(FinmaScraperService scraperService,
                                   DocumentIngestionService ingestionService) {
        this.scraperService = scraperService;
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== FINMA Document Scrape & Ingest ===");

        List<DocumentSource> documents = scraperService.discoverDocuments();
        log.info("Discovered {} documents from FINMA", documents.size());

        if (documents.isEmpty()) {
            log.warn("No documents found. Check filter configuration and page structure.");
            return;
        }

        documents.forEach(doc ->
                log.info("  - {} ({})", doc.lawName(), doc.documentType()));

        List<IngestionResult> results = ingestionService.ingestBatch(documents);

        long succeeded = results.stream().filter(IngestionResult::success).count();
        long totalChunks = results.stream().mapToInt(IngestionResult::chunksCreated).sum();
        long totalEmbeddings = results.stream().mapToInt(IngestionResult::embeddingsStored).sum();

        log.info("=== Scrape Complete ===");
        log.info("Documents: {} succeeded, {} failed",
                succeeded, results.size() - succeeded);
        log.info("Total: {} chunks, {} embeddings stored", totalChunks, totalEmbeddings);
    }
}