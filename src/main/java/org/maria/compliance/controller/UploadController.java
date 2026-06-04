package org.maria.compliance.controller;

import lombok.extern.slf4j.Slf4j;
import org.maria.compliance.service.AnalysisOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Slf4j
public class UploadController {

    private final AnalysisOrchestrator orchestrator;

    public UploadController(AnalysisOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "language", defaultValue = "de") String language,
                                                      @AuthenticationPrincipal UserDetails principal) {
        String username = principal != null ? principal.getUsername() : "anonymous";
        try {
            String taskId = orchestrator.submit(file, language, username);
            log.info("Accepted upload: taskId={} file={} user={}", taskId, file.getOriginalFilename(), username);
            return ResponseEntity.accepted().body(Map.of("taskId", taskId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}