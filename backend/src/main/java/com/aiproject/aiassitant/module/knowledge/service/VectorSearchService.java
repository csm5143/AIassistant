package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private static final int EMBEDDING_BATCH_SIZE = 32;
    private static final String UPSERT_VECTOR_SQL =
            "INSERT INTO kb_vectors (id, collection_id, document_id, chunk_id, user_id, embedding) " +
                    "VALUES (?, ?, ?, ?, ?, ?::vector) " +
                    "ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding";

    private final EmbeddingModel embeddingModel;
    private final KbChunkMapper chunkMapper;
    private final org.springframework.jdbc.core.JdbcTemplate pgJdbcTemplate;

    public List<KbChunk> vectorSearch(String query, String userId, String collectionId, int topK) {
        return vectorSearch(query, userId, collectionId, topK, 0.5f);
    }

    public List<KbChunk> vectorSearch(String query, String userId, String collectionId, int topK, float minScore) {
        if (query == null || query.isBlank()) return List.of();
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("topK must be between 1 and 100");
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            List<Float> queryVector = queryEmbedding.vectorAsList();
            float[] queryVectorArray = toFloatArray(queryVector);

            List<SearchResult> results = searchPgVector(queryVectorArray, userId, collectionId, topK * 3);

            Map<String, Float> scoreMap = results.stream()
                    .collect(Collectors.toMap(SearchResult::getChunkId, SearchResult::getScore, (a, b) -> a));

            List<String> chunkIds = results.stream()
                    .filter(r -> r.getScore() >= minScore)
                    .map(SearchResult::getChunkId)
                    .distinct()
                    .limit(topK)
                    .collect(Collectors.toList());

            if (chunkIds.isEmpty()) {
                return List.of();
            }

            LambdaQueryWrapper<KbChunk> q = new LambdaQueryWrapper<>();
            q.in(KbChunk::getId, chunkIds);
            q.eq(KbChunk::getUserId, userId);
            if (collectionId != null && !collectionId.isBlank()) q.eq(KbChunk::getCollectionId, collectionId);
            List<KbChunk> chunks = chunkMapper.selectList(q);

            chunks.sort((a, b) -> {
                float scoreA = scoreMap.getOrDefault(a.getId(), 0f);
                float scoreB = scoreMap.getOrDefault(b.getId(), 0f);
                return Float.compare(scoreB, scoreA);
            });

            return chunks;

        } catch (Exception e) {
            log.warn("Vector search unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    public List<KbChunk> vectorSearchAll(String query, String userId, int topK) {
        return vectorSearch(query, userId, null, topK);
    }

    public record VectorInput(String chunkId, String text) {}

    /**
     * The embedding API and PostgreSQL each receive at most one bounded batch at a time.
     * A failed API response cannot write that batch; failed SQL rolls back that batch.
     * Earlier committed batches are removed by the document retry path before reindexing.
     */
    public void storeVectors(String collectionId, String documentId, String userId, List<VectorInput> inputs) {
        storeVectors(collectionId, documentId, userId, inputs, committed -> {});
    }

    /**
     * Calls the progress callback after each successful database commit with the number of
     * inputs upserted in that batch (not a cumulative count). No callback fires on rollback.
     */
    public void storeVectors(String collectionId, String documentId, String userId, List<VectorInput> inputs,
                             IntConsumer onBatchCommitted) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(onBatchCommitted, "onBatchCommitted");
        for (int start = 0; start < inputs.size(); start += EMBEDDING_BATCH_SIZE) {
            List<VectorInput> batch = inputs.subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, inputs.size()));
            try {
                List<TextSegment> segments = new ArrayList<>(batch.size());
                for (VectorInput input : batch) {
                    if (input == null || input.chunkId() == null || input.chunkId().isBlank() ||
                            input.text() == null || input.text().isBlank()) {
                        throw new IllegalArgumentException("分块编号或文本为空");
                    }
                    segments.add(TextSegment.from(input.text()));
                }

                var response = embeddingModel.embedAll(segments);
                List<Embedding> embeddings = response == null ? null : response.content();
                if (embeddings == null || embeddings.size() != batch.size()) {
                    throw new IllegalStateException("嵌入服务返回的向量数量与分块数量不一致");
                }
                int dimension = -1;
                for (Embedding embedding : embeddings) {
                    if (embedding == null || embedding.vector() == null || embedding.dimension() == 0) {
                        throw new IllegalStateException("嵌入服务返回了空向量");
                    }
                    if (dimension < 0) dimension = embedding.dimension();
                    if (embedding.dimension() != dimension) {
                        throw new IllegalStateException("嵌入服务返回了不同维度的向量");
                    }
                    for (float value : embedding.vector()) {
                        if (!Float.isFinite(value)) {
                            throw new IllegalStateException("嵌入服务返回了无效向量值");
                        }
                    }
                }
                insertVectorBatch(collectionId, documentId, userId, batch, embeddings);
                onBatchCommitted.accept(batch.size());
                log.debug("Stored {} vectors for document {}", batch.size(), documentId);
            } catch (Exception e) {
                log.error("Failed to store vectors for document {} at batch offset {}: {}", documentId, start, e.getMessage());
                throw new IllegalStateException("向量批量写入失败，请稍后重试", e);
            }
        }
    }

    private void insertVectorBatch(String collectionId, String documentId, String userId,
                                   List<VectorInput> batch, List<Embedding> embeddings) throws SQLException {
        try (Connection conn = getPgConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(UPSERT_VECTOR_SQL)) {
                    for (int i = 0; i < batch.size(); i++) {
                        String chunkId = batch.get(i).chunkId();
                        ps.setString(1, chunkId);
                        ps.setString(2, collectionId);
                        ps.setString(3, documentId);
                        ps.setString(4, chunkId);
                        ps.setString(5, userId);
                        ps.setString(6, "[" + formatVector(embeddings.get(i).vector()) + "]");
                        ps.addBatch();
                    }
                    int[] counts = ps.executeBatch();
                    if (counts.length != batch.size() ||
                            Arrays.stream(counts).anyMatch(count -> count == Statement.EXECUTE_FAILED)) {
                        throw new SQLException("向量库未确认全部分块写入成功");
                    }
                }
                conn.commit();
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { e.addSuppressed(rollbackFailure); }
                if (e instanceof SQLException sqlException) throw sqlException;
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public void storeVector(String chunkId, String collectionId, String documentId, String userId, String text) {
        try {
            Embedding embedding = embeddingModel.embed(text).content();
            List<Float> vector = embedding.vectorAsList();

            String vectorString = "[" + formatVector(vector) + "]";

            try (Connection conn = getPgConnection();
                 PreparedStatement ps = conn.prepareStatement(UPSERT_VECTOR_SQL)) {

                ps.setString(1, chunkId);
                ps.setString(2, collectionId);
                ps.setString(3, documentId);
                ps.setString(4, chunkId);
                ps.setString(5, userId);
                ps.setString(6, vectorString);
                ps.executeUpdate();
            }

            log.debug("Stored vector for chunk: {}", chunkId);
        } catch (Exception e) {
            log.error("Failed to store vector for chunk {}: {}", chunkId, e.getMessage());
            throw new IllegalStateException("向量写入失败，请稍后重试", e);
        }
    }

    public void deleteVectorsByChunk(String chunkId) {
        try (Connection conn = getPgConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM kb_vectors WHERE chunk_id = ?")) {
            ps.setString(1, chunkId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("向量删除失败，请稍后重试", e);
        }
    }

    public void deleteVectorsByDocument(String documentId) {
        try (Connection conn = getPgConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM kb_vectors WHERE document_id = ?")) {
            ps.setString(1, documentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("文档向量删除失败，请稍后重试", e);
        }
    }

    /** Read-only lookup for resuming a document after an interrupted vectorization. */
    public Set<String> existingVectorChunkIds(String documentId) {
        try (Connection conn = getPgConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT chunk_id FROM kb_vectors WHERE document_id = ?")) {
            ps.setString(1, documentId);
            Set<String> chunkIds = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) chunkIds.add(rs.getString(1));
            }
            return chunkIds;
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取文档向量进度", e);
        }
    }

    public long countVectorsByDocument(String documentId) {
        try (Connection conn = getPgConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM kb_vectors WHERE document_id = ?")) {
            ps.setString(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取文档向量进度", e);
        }
    }

    private List<SearchResult> searchPgVector(float[] queryVector, String userId, String collectionId, int limit) {
        List<SearchResult> results = new ArrayList<>();

        String vectorString = "[" + formatVector(toFloatList(queryVector)) + "]";

        StringBuilder sql = new StringBuilder(
                "SELECT chunk_id, (embedding <=> ?::vector) AS distance FROM kb_vectors WHERE user_id = ?");

        if (collectionId != null && !collectionId.isBlank()) {
            sql.append(" AND collection_id = ?");
        }

        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");

        try (Connection conn = getPgConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            int idx = 1;
            ps.setString(idx++, vectorString);
            ps.setString(idx++, userId);
            if (collectionId != null && !collectionId.isBlank()) {
                ps.setString(idx++, collectionId);
            }
            ps.setString(idx++, vectorString);
            ps.setInt(idx, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SearchResult sr = new SearchResult();
                    sr.setChunkId(rs.getString("chunk_id"));
                    sr.setScore(1.0f - rs.getFloat("distance"));
                    results.add(sr);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("向量检索暂不可用", e);
        }

        return results;
    }

    private List<KbChunk> fallbackToChronological(String collectionId, String userId, int topK) {
        LambdaQueryWrapper<KbChunk> q = new LambdaQueryWrapper<>();
        if (collectionId != null && !collectionId.isBlank()) {
            q.eq(KbChunk::getCollectionId, collectionId);
        }
        q.eq(KbChunk::getUserId, userId);
        q.orderByDesc(KbChunk::getCreatedAt);
        q.last("LIMIT " + topK);
        return chunkMapper.selectList(q);
    }

    private Connection getPgConnection() throws SQLException {
        return pgJdbcTemplate.getDataSource().getConnection();
    }

    private float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private String formatVector(List<Float> vector) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.ROOT, "%.8f", vector.get(i)));
        }
        return sb.toString();
    }

    private String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private static class SearchResult {
        private String chunkId;
        private float score;

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }
        public float getScore() { return score; }
        public void setScore(float score) { this.score = score; }
    }
}
