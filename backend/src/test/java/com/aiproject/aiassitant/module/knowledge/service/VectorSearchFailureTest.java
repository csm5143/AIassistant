package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VectorSearchFailureTest {
    @Test void failedEmbeddingCannotMarkVectorWriteSuccessful() {
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        JdbcTemplate pg = mock(JdbcTemplate.class);
        VectorSearchService service = new VectorSearchService(embedding, mock(KbChunkMapper.class), pg);

        assertThrows(IllegalStateException.class,
                () -> service.storeVector("chunk", "collection", "document", "owner", "content"));
        assertTrue(service.vectorSearch("query", "owner", null, 5).isEmpty());
        verifyNoInteractions(pg);
    }
}
