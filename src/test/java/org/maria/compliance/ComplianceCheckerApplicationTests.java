package org.maria.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ComplianceCheckerApplicationTests {

    @MockitoBean EmbeddingModel embeddingModel;
    @MockitoBean ChatModel chatModel;
    @MockitoBean StreamingChatModel streamingChatModel;
    @MockitoBean EmbeddingStore<TextSegment> embeddingStore;
    @MockitoBean ObjectMapper objectMapper;
    @MockitoBean JdbcClient jdbcClient;

    @Test
    void contextLoads() {
    }

}
