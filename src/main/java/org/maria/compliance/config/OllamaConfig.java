package org.maria.compliance.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Configuration
@Profile("!test")
public class OllamaConfig {

    @Value("${compliance.ollama.base-url}")
    private String baseUrl;

    @Value("${compliance.ollama.chat-model}")
    private String chatModelName;

    @Value("${compliance.ollama.embedding-model}")
    private String embeddingModelName;

    @Value("${compliance.ollama.timeout}")
    private Duration timeout;

    @Value("${compliance.ollama.max-retries:3}")
    private int maxRetries;

    // Ollama's default context window is small (4096 tokens for most models). The
    // compliance prompt is regulatory chunks + the policy section + rules + schema,
    // which easily exceeds that — and Ollama truncates silently, so the model never
    // sees part of the prompt and returns malformed or context-free answers. This was
    // a likely contributor to the JSON parse retries ADR-005 was written around.
    // qwen2.5 supports 32k; 16k balances coverage against KV-cache memory on CPU.
    @Value("${compliance.ollama.num-ctx:16384}")
    private int numCtx;

    @Bean
    public ChatModel chatModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(chatModelName)
                .temperature(0.0)
                .numCtx(numCtx)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel() {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(chatModelName)
                .temperature(0.0)
                .numCtx(numCtx)
                .timeout(timeout)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(embeddingModelName)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
    }

    @Bean
    public ApplicationRunner ollamaModelEnsureRunner(ObjectMapper objectMapper) {
        return args -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            ensureModelAvailable(httpClient, objectMapper, embeddingModelName);
            ensureModelAvailable(httpClient, objectMapper, chatModelName);
        };
    }

    private void ensureModelAvailable(HttpClient httpClient, ObjectMapper objectMapper,
                                      String modelName) throws Exception {
        HttpRequest tagsRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        String tagsBody = httpClient.send(tagsRequest, HttpResponse.BodyHandlers.ofString()).body();

        if (isInstalled(objectMapper, tagsBody, modelName)) {
            log.info("Ollama model available: {}", modelName);
            return;
        }

        log.info("Ollama model '{}' not found — pulling (this may take several minutes)...", modelName);

        String pullBody = String.format("{\"model\":\"%s\",\"stream\":false}", modelName);
        HttpRequest pullRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/pull"))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(pullBody))
                .build();

        HttpResponse<String> pullResponse = httpClient.send(pullRequest, HttpResponse.BodyHandlers.ofString());

        if (pullResponse.statusCode() != 200) {
            throw new IllegalStateException(
                    "Failed to pull Ollama model '" + modelName + "': " + pullResponse.body());
        }

        log.info("Ollama model '{}' pulled successfully.", modelName);
    }

    private boolean isInstalled(ObjectMapper objectMapper, String tagsBody, String modelName) {
        String wanted = modelName.contains(":") ? modelName : modelName + ":latest";
        try {
            JsonNode models = objectMapper.readTree(tagsBody).path("models");
            for (JsonNode model : models) {
                if (wanted.equals(model.path("name").asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Ollama /api/tags response, assuming model '{}' is missing: {}",
                    modelName, e.getMessage());
        }
        return false;
    }
}