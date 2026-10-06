package com.aiproject.aiassitant.module.documentqa;

import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.module.chat.mapper.ChatMessageMapper;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.documentqa.service.DocumentQaService;
import com.aiproject.aiassitant.module.documentqa.service.DocumentQaSessionStore;
import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import com.aiproject.aiassitant.security.AppPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentQaPersistenceTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void serializesChunksAndVectorsForRedisReload() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked") SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        DocumentQaSessionStore store = new DocumentQaSessionStore(redis, new ObjectMapper());
        var session = new DocumentQaService.DocSession("s1", "owner", "说明.txt", List.of("甲乙"),
                List.of(new DocumentQaService.ChunkEmbedding("甲乙",
                        dev.langchain4j.data.embedding.Embedding.from(new float[]{0.3f, 0.7f}))));

        store.save(session);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("document-qa:session:s1"), json.capture(), eq(Duration.ofMinutes(30)));
        when(values.get("document-qa:session:s1")).thenReturn(json.getValue());
        var restored = store.load("s1");

        assertEquals("owner", restored.ownerId());
        assertEquals("甲乙", restored.chunks().get(0).text());
        assertArrayEquals(new float[]{0.3f, 0.7f}, restored.chunks().get(0).vector());
        verify(sets).add("document-qa:user:owner", "s1");
    }

    @Test void reloadsOnlyOwnedChatSessionAfterBackendCacheIsEmpty() {
        var principal = new AppPrincipal("owner", "owner", "user", List.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "token", List.of()));
        DocumentQaSessionStore store = mock(DocumentQaSessionStore.class);
        when(store.load("s1")).thenReturn(new DocumentQaSessionStore.StoredSession("s1", "owner", "说明.txt",
                List.of(new DocumentQaSessionStore.StoredChunk("甲乙", new float[]{1, 0})),
                System.currentTimeMillis(), System.currentTimeMillis(), null, List.of(), null));
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        ChatSession chat = new ChatSession();
        chat.setId("s1");
        chat.setUserId("owner");
        when(sessions.selectById("s1")).thenReturn(chat);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        ChatMessage lastReply = new ChatMessage();
        lastReply.setRole("assistant");
        lastReply.setContent("负责人是林岚");
        ChatMessage firstQuestion = new ChatMessage();
        firstQuestion.setRole("user");
        firstQuestion.setContent("谁负责海岚项目？");
        when(messages.selectList(any())).thenReturn(List.of(lastReply, firstQuestion));
        var service = new DocumentQaService(mock(EmbeddingModel.class), mock(SourceDocumentParser.class),
                sessions, messages, store);

        var restored = service.getSession("s1");
        assertNotNull(restored);
        assertEquals("甲乙", restored.chunks.get(0));
        assertEquals(2, restored.history.size());
        assertTrue(restored.history.get(0) instanceof dev.langchain4j.data.message.UserMessage);
        assertEquals(1, restored.search(dev.langchain4j.data.embedding.Embedding.from(new float[]{1, 0}), 5, 0.3).size());
        verify(store).save(restored);

        chat.setUserId("someone-else");
        var secondService = new DocumentQaService(mock(EmbeddingModel.class), mock(SourceDocumentParser.class),
                sessions, messages, store);
        assertNull(secondService.getSession("s1"));
    }
}
