package org.maria.compliance.service;

import org.maria.compliance.model.ChunkResult;
import org.maria.compliance.model.PolicySection;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface PdfProcessingService {

    String extractText(MultipartFile file);

    List<PolicySection> detectSections(String text);

    List<ChunkResult> chunkText(String text, int chunkSize, int overlap);

    List<ChunkResult> processAndChunk(MultipartFile file);

}