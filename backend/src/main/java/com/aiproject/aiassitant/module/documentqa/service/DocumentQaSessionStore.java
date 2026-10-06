package com.aiproject.aiassitant.module.documentqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Stores temporary document text and embeddings in the persistent Redis volume. */
@Component
@RequiredArgsConstructor
public class DocumentQaSessionStore {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String SESSION_PREFIX = "document-qa:session:";
    private static final String USER_PREFIX = "document-qa:user:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public void save(DocumentQaService.DocSession session) {
        List<StoredChunk> chunks = new ArrayList<>();
        for (DocumentQaService.ChunkEmbedding chunk : session.chunkEmbeddings) {
            chunks.add(new StoredChunk(chunk.text, chunk.embedding.vector()));
        }
        StoredSession value = new StoredSession(session.sessionId, session.ownerId, session.fileName,
                chunks, session.createdAt, session.lastAccess(), session.source, session.locations, session.sourceVersion);
        try {
            redis.opsForValue().set(SESSION_PREFIX + session.sessionId, mapper.writeValueAsString(value), TTL);
            String userKey = USER_PREFIX + session.ownerId;
            redis.opsForSet().add(userKey, session.sessionId);
            redis.expire(userKey, TTL.plusMinutes(1));
        } catch (Exception e) {
            throw new IllegalStateException("无法保存临时文档会话", e);
        }
    }

    public StoredSession load(String sessionId) {
        try {
            String json = redis.opsForValue().get(SESSION_PREFIX + sessionId);
            return json == null ? null : mapper.readValue(json, StoredSession.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取临时文档会话", e);
        }
    }

    public Set<String> listIds(String userId) {
        Set<String> ids = redis.opsForSet().members(USER_PREFIX + userId);
        return ids == null ? Set.of() : ids;
    }

    public void delete(String sessionId, String userId) {
        redis.delete(SESSION_PREFIX + sessionId);
        redis.opsForSet().remove(USER_PREFIX + userId, sessionId);
    }

    public record StoredChunk(String text, float[] vector) {}
    public record StoredSession(String sessionId, String ownerId, String fileName,
                                List<StoredChunk> chunks, long createdAt, long lastAccess,
                                com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser.SourceDocument source,
                                List<com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser.LocatedChunk> locations,
                                String sourceVersion) {}
}
