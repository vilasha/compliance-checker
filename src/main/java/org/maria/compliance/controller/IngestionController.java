package org.maria.compliance.controller;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.DocumentSource;
import org.maria.compliance.model.IngestionResult;
import org.maria.compliance.service.DocumentIngestionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
@Slf4j
public class IngestionController {

    private final DocumentIngestionService ingestionService;
    private final List<String> allowedHosts;

    public IngestionController(DocumentIngestionService ingestionService,
                               @Value("${compliance.regulatory.allowed-ingest-hosts}") List<String> allowedHosts) {
        this.ingestionService = ingestionService;
        this.allowedHosts = allowedHosts;
    }

    @PostMapping("/url")
    public ResponseEntity<IngestionResult> ingestFromUrl(@RequestParam String url) {
        log.info("Ingestion request for URL: {}", url);

        // Host allowlist instead of the old endsWith(".pdf") check, which protected
        // nothing (any URL can serve PDF bytes) while rejecting legitimate regulator
        // links with query strings (FINMA's Sitecore URLs end in ?sc_lang=de).
        // The server fetches this URL itself, so without the allowlist an
        // authenticated user could probe internal addresses through it (SSRF).
        // Whether the response is actually a PDF is verified by magic bytes after
        // download in DocumentIngestionServiceImpl
        if (!isAllowedSource(url)) {
            return ResponseEntity.badRequest().body(IngestionResult.builder()
                    .url(url)
                    .success(false)
                    .errorMessage("URL host is not an allowed regulatory source. Allowed hosts: "
                            + String.join(", ", allowedHosts))
                    .build());
        }

        IngestionResult result = ingestionService.ingestFromUrl(url);
        return result.success() ? ResponseEntity.ok(result) : ResponseEntity.unprocessableEntity().body(result);
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(@RequestBody List<DocumentSource> sources) {
        log.info("Batch ingestion request for {} documents", sources.size());

        List<DocumentSource> rejected = sources.stream()
                .filter(source -> !isAllowedSource(source.url()))
                .toList();
        if (!rejected.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Batch contains URLs outside the allowed regulatory sources",
                    "rejectedUrls", rejected.stream().map(DocumentSource::url).toList(),
                    "allowedHosts", allowedHosts));
        }

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

    private boolean isAllowedSource(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase();
        return allowedHosts.stream()
                .map(String::toLowerCase)
                .anyMatch(allowed -> normalized.equals(allowed) || normalized.endsWith("." + allowed));
    }
}