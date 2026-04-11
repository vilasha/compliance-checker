package org.maria.compliance.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.maria.compliance.model.ChunkResult;
import org.maria.compliance.model.PolicySection;
import org.maria.compliance.exception.PdfProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class PdfProcessingServiceImpl implements PdfProcessingService {

    @Value("${compliance.pdf.section-detection.enabled:true}")
    private boolean sectionDetectionEnabled;

    @Value("${compliance.pdf.section-detection.heading-patterns:^(Art\\.|§|Article|Section|Chapter|Artikel)}")
    private String headingPatterns;

    @Value("${compliance.rag.chunk-size:1000}")
    private int defaultChunkSize;

    @Value("${compliance.rag.chunk-overlap:200}")
    private int defaultChunkOverlap;

    @Override
    public String extractText(MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.debug("Extracted {} characters from PDF: {}", text.length(), file.getOriginalFilename());
            return text;
        } catch (IOException e) {
            log.error("Failed to extract text from PDF: {}", file.getOriginalFilename(), e);
            throw new PdfProcessingException("Failed to extract text from PDF: " + file.getOriginalFilename(), e);
        }
    }

    @Override
    public List<PolicySection> detectSections(String text) {
        if (!sectionDetectionEnabled) {
            return List.of(PolicySection.builder()
                    .sectionNumber(1)
                    .heading("Full Document")
                    .content(text)
                    .startPosition(0)
                    .endPosition(text.length())
                    .build());
        }

        List<PolicySection> sections = new ArrayList<>();
        Pattern pattern = Pattern.compile(headingPatterns, Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);

        List<Integer> sectionStarts = new ArrayList<>();
        List<String> sectionHeadings = new ArrayList<>();

        while (matcher.find()) {
            sectionStarts.add(matcher.start());
            int lineEnd = text.indexOf('\n', matcher.start());
            if (lineEnd == -1) lineEnd = text.length();
            sectionHeadings.add(text.substring(matcher.start(), lineEnd).trim());
        }

        if (sectionStarts.isEmpty()) {
            return List.of(PolicySection.builder()
                    .sectionNumber(1)
                    .heading("Full Document")
                    .content(text)
                    .startPosition(0)
                    .endPosition(text.length())
                    .build());
        }

        for (int i = 0; i < sectionStarts.size(); i++) {
            int start = sectionStarts.get(i);
            int end = (i < sectionStarts.size() - 1) ? sectionStarts.get(i + 1) : text.length();

            String sectionContent = text.substring(start, end).trim();

            sections.add(PolicySection.builder()
                    .sectionNumber(i + 1)
                    .heading(sectionHeadings.get(i))
                    .content(sectionContent)
                    .startPosition(start)
                    .endPosition(end)
                    .build());
        }

        log.debug("Detected {} sections in document", sections.size());
        return sections;
    }

    @Override
    public List<ChunkResult> chunkText(String text, int chunkSize, int overlap) {
        List<ChunkResult> chunks = new ArrayList<>();
        int position = 0;
        int chunkIndex = 0;

        while (position < text.length()) {
            int end = Math.min(position + chunkSize, text.length());

            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > position) {
                    end = lastSpace;
                }
            }

            String chunkText = text.substring(position, end).trim();

            chunks.add(ChunkResult.builder()
                    .text(chunkText)
                    .chunkIndex(chunkIndex)
                    .sectionHeading(null)
                    .startPosition(position)
                    .endPosition(end)
                    .build());

            int nextPosition = end - overlap;
            if (nextPosition <= position) {
                nextPosition = end;
            }
            position = nextPosition;

            chunkIndex++;
        }

        log.debug("Created {} chunks (size={}, overlap={}) from {} characters",
                chunks.size(), chunkSize, overlap, text.length());
        return chunks;
    }

    @Override
    public List<ChunkResult> processAndChunk(MultipartFile file) {
        String text = extractText(file);
        List<PolicySection> sections = detectSections(text);

        List<ChunkResult> allChunks = new ArrayList<>();

        for (PolicySection section : sections) {
            List<ChunkResult> sectionChunks = chunkText(
                    section.content(),
                    defaultChunkSize,
                    defaultChunkOverlap
            );

            sectionChunks = sectionChunks.stream()
                    .map(chunk -> ChunkResult.builder()
                            .text(chunk.text())
                            .chunkIndex(allChunks.size())
                            .sectionHeading(section.heading())
                            .startPosition(chunk.startPosition())
                            .endPosition(chunk.endPosition())
                            .build())
                    .toList();

            allChunks.addAll(sectionChunks);
        }

        log.info("Processed PDF '{}': {} sections, {} chunks",
                file.getOriginalFilename(), sections.size(), allChunks.size());
        return allChunks;
    }
}