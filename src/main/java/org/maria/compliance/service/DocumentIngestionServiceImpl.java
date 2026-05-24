package org.maria.compliance.service;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.ChunkResult;
import org.maria.compliance.model.DocumentSource;
import org.maria.compliance.model.IngestionResult;
import org.maria.compliance.model.RegulatoryChunk;
import org.maria.compliance.repository.RegulatoryMetadataRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");

    private final PdfProcessingService pdfProcessingService;
    private final EmbeddingService embeddingService;
    private final RegulatoryMetadataRepository metadataRepository;
    private final HttpClient httpClient;

    public DocumentIngestionServiceImpl(PdfProcessingService pdfProcessingService,
                                        EmbeddingService embeddingService,
                                        RegulatoryMetadataRepository metadataRepository) {
        this.pdfProcessingService = pdfProcessingService;
        this.embeddingService = embeddingService;
        this.metadataRepository = metadataRepository;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public IngestionResult ingestFromUrl(String pdfUrl) {
        DocumentSource source = DocumentSource.builder()
                .url(pdfUrl)
                .fileName(extractFileName(pdfUrl))
                .language(detectLanguage(pdfUrl))
                .lawName(extractLawName(pdfUrl))
                .year(extractYear(pdfUrl))
                .downloadTimestamp(LocalDateTime.now())
                .build();
        return ingestFromUrl(source);
    }

    @Override
    public IngestionResult ingestFromUrl(DocumentSource source) {
        long startTime = System.currentTimeMillis();
        String fileName = source.fileName() != null ? source.fileName() : extractFileName(source.url());

        if (metadataRepository.existsBySourceUrl(source.url())) {
            log.info("Document already ingested, skipping: {}", source.url());
            return IngestionResult.builder()
                    .url(source.url())
                    .fileName(fileName)
                    .success(false)
                    .errorMessage("Document already exists in the knowledge base")
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .embeddingIds(List.of())
                    .build();
        }

        log.info("Starting ingestion: {} ({})", fileName, source.url());

        try {
            byte[] pdfContent = downloadPdf(source.url());
            log.info("Downloaded {} ({} KB)", fileName, pdfContent.length / 1024);

            List<ChunkResult> chunks = pdfProcessingService.processAndChunk(pdfContent, fileName);

            if (chunks.isEmpty()) {
                log.warn("No chunks produced from {}", fileName);
                return buildResult(source, fileName, 0, List.of(),
                        System.currentTimeMillis() - startTime, true, null);
            }

            List<RegulatoryChunk> regulatoryChunks = chunks.stream()
                    .map(chunk -> toRegulatoryChunk(chunk, source, fileName))
                    .toList();

            List<String> embeddingIds = embeddingService.storeRegulatoryChunks(regulatoryChunks);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Ingestion complete: {} → {} chunks, {} embeddings in {}ms",
                    fileName, chunks.size(), embeddingIds.size(), elapsed);

            return buildResult(source, fileName, chunks.size(), embeddingIds, elapsed, true, null);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Ingestion failed for {}: {}", fileName, e.getMessage(), e);
            return buildResult(source, fileName, 0, List.of(), elapsed, false, e.getMessage());
        }
    }

    @Override
    public List<IngestionResult> ingestBatch(List<DocumentSource> sources) {
        log.info("Starting batch ingestion of {} documents", sources.size());
        List<IngestionResult> results = new ArrayList<>();
        int success = 0;
        int failed = 0;

        for (int i = 0; i < sources.size(); i++) {
            DocumentSource source = sources.get(i);
            log.info("Processing document {}/{}: {}", i + 1, sources.size(), source.url());

            IngestionResult result = ingestFromUrl(source);
            results.add(result);

            if (result.success()) {
                success++;
            } else {
                failed++;
            }
        }

        log.info("Batch ingestion complete: {} succeeded, {} failed out of {} total",
                success, failed, sources.size());
        return results;
    }

    private byte[] downloadPdf(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "ComplianceChecker/1.0 (Regulatory Document Ingestion)")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
        }

        return response.body();
    }

    private RegulatoryChunk toRegulatoryChunk(ChunkResult chunk, DocumentSource source, String fileName) {
        return RegulatoryChunk.builder()
                .lawName(source.lawName() != null ? source.lawName() : fileName)
                .year(source.year() != null ? source.year() : LocalDateTime.now().getYear())
                .language(source.language() != null ? source.language() : "de")
                .sectionPath(chunk.sectionHeading())
                .originalText(chunk.text())
                .sourceUrl(source.url())
                .build();
    }

    private IngestionResult buildResult(DocumentSource source, String fileName, int chunks,
                                        List<String> embeddingIds, long elapsed,
                                        boolean success, String error) {
        return IngestionResult.builder()
                .url(source.url())
                .fileName(fileName)
                .chunksCreated(chunks)
                .embeddingsStored(embeddingIds.size())
                .processingTimeMs(elapsed)
                .success(success)
                .errorMessage(error)
                .embeddingIds(embeddingIds)
                .build();
    }

    private String extractFileName(String url) {
        String path = URI.create(url).getPath();
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String detectLanguage(String url) {
        if (url.contains("/de/") || url.contains("sc_lang=de")) return "de";
        if (url.contains("/fr/") || url.contains("sc_lang=fr")) return "fr";
        if (url.contains("/it/") || url.contains("sc_lang=it")) return "it";
        if (url.contains("/en/") || url.contains("sc_lang=en")) return "en";
        return "de";
    }

    private String extractLawName(String url) {
        String fileName = extractFileName(url);
        String withoutExtension = fileName.replaceAll("\\.pdf$", "");
        return withoutExtension
                .replaceAll("_", " ")
                .replaceAll("-", " ");
    }

    private Integer extractYear(String url) {
        Matcher matcher = YEAR_PATTERN.matcher(url);
        Integer lastYear = null;
        while (matcher.find()) {
            lastYear = Integer.parseInt(matcher.group(1));
        }
        return lastYear;
    }
}