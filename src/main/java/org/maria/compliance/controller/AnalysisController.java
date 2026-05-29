package org.maria.compliance.controller;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.model.AnalyzeRequest;
import org.maria.compliance.model.ComplianceAnalysisResult;
import org.maria.compliance.service.SingleQueryRagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Slf4j
public class AnalysisController {

    private final SingleQueryRagService ragService;

    public AnalysisController(SingleQueryRagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<ComplianceAnalysisResult> analyze(@RequestBody AnalyzeRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("Analyze request: language={}, text length={}",
                request.language(), request.text().length());

        ComplianceAnalysisResult result = ragService.analyze(request.text(), request.language());
        return ResponseEntity.ok(result);
    }
}