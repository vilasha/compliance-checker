package org.maria.compliance.controller;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.DocumentSource;
import org.maria.compliance.model.IngestionResult;
import org.maria.compliance.service.DocumentIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
@Slf4j
public class IngestionController {

    private final DocumentIngestionService ingestionService;

    public IngestionController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/url")
    public ResponseEntity<IngestionResult> ingestFromUrl(@RequestParam String url) {
        log.info("Ingestion request for URL: {}", url);

        if (!url.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(IngestionResult.builder()
                    .url(url)
                    .success(false)
                    .errorMessage("URL must point to a PDF file")
                    .build());
        }

        IngestionResult result = ingestionService.ingestFromUrl(url);
        return result.success() ? ResponseEntity.ok(result) : ResponseEntity.unprocessableEntity().body(result);
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(@RequestBody List<DocumentSource> sources) {
        log.info("Batch ingestion request for {} documents", sources.size());

        List<IngestionResult> results = ingestionService.ingestBatch(sources);

        long succeeded = results.stream().filter(IngestionResult::success).count();
        long failed = results.size() - succeeded;

        return ResponseEntity.ok(Map.of(
                "total", results.size(),
                "succeeded", succeeded,
                "failed", failed,
                "results", results
        ));
    }
}