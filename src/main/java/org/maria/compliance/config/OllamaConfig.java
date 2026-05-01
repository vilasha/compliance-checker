package org.maria.compliance.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

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
}