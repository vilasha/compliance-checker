package org.maria.compliance.config;

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

    @Bean
    public ChatModel chatModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(chatModelName)
                .temperature(0.0)
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
    public ApplicationRunner ollamaModelEnsureRunner() {
        return args -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            ensureModelAvailable(httpClient, embeddingModelName);
            ensureModelAvailable(httpClient, chatModelName);
        };
    }

    private void ensureModelAvailable(HttpClient httpClient, String modelName) throws Exception {
        HttpRequest tagsRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        String tagsBody = httpClient.send(tagsRequest, HttpResponse.BodyHandlers.ofString()).body();
        String baseName = modelName.contains(":") ? modelName.split(":")[0] : modelName;

        if (tagsBody.contains("\"" + baseName)) {
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
}