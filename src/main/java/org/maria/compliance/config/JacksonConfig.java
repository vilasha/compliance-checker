package org.maria.compliance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the Jackson 2 ObjectMapper used throughout the codebase
 * (report persistence, SSE event payloads, LLM response parsing).
 * <p>
 * This bean is required, not optional: Spring Boot 4 auto-configures Jackson 3
 * ({@code tools.jackson.databind.ObjectMapper}) and no longer supplies a
 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean. Until the codebase
 * and its dependencies (LangChain4j is still on Jackson 2) migrate, we define
 * the Jackson 2 mapper ourselves.
 * <p>
 * WRITE_DATES_AS_TIMESTAMPS is disabled so {@code Instant} fields — e.g.
 * {@code TaskEvent.timestamp} streamed over SSE — serialize as ISO-8601
 * strings instead of epoch decimals
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}