package org.maria.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.DocumentSource;
import org.maria.compliance.repository.RegulatoryMetadataRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Profile("scrape")
@Slf4j
public class HttpClientFinmaScraperService implements FinmaScraperService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final RegulatoryMetadataRepository metadataRepository;
    @Value("${compliance.scraper.finma.api:https://www.finma.ch/api/search/getresult}")
    private String finmaApiUrl;
    @Value("${compliance.scraper.finma.page-size:10}")
    private int pageSize;
    @Value("${compliance.scraper.finma.guid.datasource:{E02680B6-2600-4C66-BD5B-57BF955A97A8}}")
    private String DATASOURCE_GUID;
    @Value("${compliance.scraper.finma.guid.wegleitung}")
    private String wegleitungGuid;
    @Value("${compliance.scraper.finma.guid.aufsichtsmitteilung}")
    private String aufsichtsmitteilungGuid;
    @Value("${compliance.scraper.finma.max-pages:50}")
    private int maxPages;

    public HttpClientFinmaScraperService(RegulatoryMetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public List<DocumentSource> discoverDocuments() {
        log.info("Starting FINMA document discovery via API");
        Map<String, DocumentSource> allDocuments = new LinkedHashMap<>();

        List<FilterPass> filterPasses = List.of(
                new FilterPass("Wegleitung", "Guideline", wegleitungGuid),
                new FilterPass("FINMA-Aufsichtsmitteilung", "FINMA Guidance", aufsichtsmitteilungGuid)
        );

        for (FilterPass pass : filterPasses) {
            log.info("=== API query: {} ===", pass.label());
            List<DocumentSource> found = runApiQuery(pass);
            int newCount = 0;
            for (DocumentSource doc : found) {
                if (allDocuments.putIfAbsent(doc.url(), doc) == null) {
                    newCount++;
                }
            }
            log.info("'{}': {} documents ({} new, {} duplicates)",
                    pass.label(), found.size(), newCount, found.size() - newCount);
        }

        log.info("Total unique documents discovered: {}", allDocuments.size());

        List<DocumentSource> newDocuments = allDocuments.values().stream()
                .filter(doc -> !metadataRepository.existsBySourceUrl(doc.url()))
                .toList();

        int skipped = allDocuments.size() - newDocuments.size();
        if (skipped > 0) {
            log.info("Skipped {} already ingested documents, {} new to process", skipped, newDocuments.size());
        }

        return newDocuments;
    }

    private List<DocumentSource> runApiQuery(FilterPass pass) {
        List<DocumentSource> allResults = new ArrayList<>();
        int skip = 0;

        while (skip / pageSize < maxPages) {
            try {
                String body = buildRequestBody(pass.guid());
                String url = skip == 0 ? finmaApiUrl : finmaApiUrl + "?Skip=" + skip;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .header("Accept", "application/json, text/javascript, */*; q=0.01")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://www.finma.ch/de/dokumente/")
                        .header("User-Agent", "ComplianceChecker/1.0")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.warn("API returned HTTP {} for {}", response.statusCode(), pass.label());
                    break;
                }

                String responseBody = response.body();
                List<DocumentSource> pageResults = extractDocuments(responseBody, pass.documentType());

                if (pageResults.isEmpty()) {
                    log.info("No more results at skip={}", skip);
                    break;
                }

                allResults.addAll(pageResults);
                log.info("Skip {}: found {} documents (total so far: {})", skip, pageResults.size(), allResults.size());
                skip += pageSize;

            } catch (Exception e) {
                log.error("API query failed for '{}' at skip={}: {}", pass.label(), skip, e.getMessage());
                break;
            }
        }

        return allResults;
    }

    private String buildRequestBody(String dokumentenartGuid) {
        return "ds=" + encode(DATASOURCE_GUID) +
                "&Dokumentenart=" + encode(dokumentenartGuid) +
                "&Order=4";
    }

    private List<DocumentSource> extractDocuments(String responseBody, String documentType) {
        List<DocumentSource> sources = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode items = root.path("Items");

            if (!items.isArray() || items.isEmpty()) {
                return sources;
            }

            for (JsonNode item : items) {
                String pdfUrl = findGermanPdfUrl(item);
                if (pdfUrl == null) continue;

                if (!pdfUrl.startsWith("http")) {
                    pdfUrl = "https://www.finma.ch" + pdfUrl;
                }

                String title = findTitle(item);

                sources.add(DocumentSource.builder()
                        .url(pdfUrl)
                        .fileName(extractFileName(pdfUrl))
                        .documentType(documentType)
                        .language("de")
                        .lawName(title != null ? title : extractFileName(pdfUrl))
                        .year(extractYear(pdfUrl))
                        .downloadTimestamp(now)
                        .build());
            }
        } catch (Exception e) {
            log.error("Failed to parse API response: {}", e.getMessage());
        }

        return sources;
    }

    private String findGermanPdfUrl(JsonNode item) {
        JsonNode links = item.path("OtherLanguageLinks");
        if (links.isArray()) {
            for (JsonNode link : links) {
                String name = link.path("Name").asText("");
                String url = link.path("Url").asText("");
                if ("DE".equalsIgnoreCase(name) && url.toLowerCase().contains(".pdf")) {
                    return url;
                }
            }
            // Fallback: take the first PDF link regardless of language
            for (JsonNode link : links) {
                String url = link.path("Url").asText("");
                if (url.toLowerCase().contains(".pdf")) {
                    return url;
                }
            }
        }
        return null;
    }

    private String findTitle(JsonNode item) {
        for (String field : List.of("Title", "Name", "DocumentTitle", "Description")) {
            String value = item.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private Integer extractYear(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(20\\d{2})").matcher(url);
        Integer last = null;
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private String extractFileName(String url) {
        String path = url.split("\\?")[0];
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record FilterPass(String label, String documentType, String guid) {
    }
}