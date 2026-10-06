package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.chat.mapper.ChatMessageMapper;
import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Service
public class DocumentQaService {

    private final EmbeddingModel embeddingModel;
    private final SourceDocumentParser sourceParser;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final DocumentQaSessionStore sessionStore;
    private final Map<String, DocSession> sessions = new ConcurrentHashMap<>();

    public DocumentQaService(EmbeddingModel embeddingModel, SourceDocumentParser sourceParser, ChatSessionMapper chatSessionMapper,
                             ChatMessageMapper chatMessageMapper, DocumentQaSessionStore sessionStore) {
        this.embeddingModel = embeddingModel;
        this.sourceParser = sourceParser;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sessionStore = sessionStore;
    }

    /** Upload and process a document. Uses chatSessionId if provided, otherwise generates one. */
    public Map<String, Object> upload(MultipartFile file, String chatSessionId) throws IOException {
        return upload(file, chatSessionId, progress -> {});
    }

    public record UploadProgress(String phase, int completed, int total) {}

    interface UploadCheckpoint {
        default SourceDocumentParser.SourceDocument source() { return null; }
        default void saveSource(SourceDocumentParser.SourceDocument source) {}
        default DocumentEmbeddingBatcher.Result embeddings() { return null; }
        default void saveEmbeddings(DocumentEmbeddingBatcher.Result result) {}
        default String sourceVersion() { return UUID.randomUUID().toString(); }
        default void publish(Runnable action) { action.run(); }
    }

    public Map<String, Object> upload(MultipartFile file, String chatSessionId,
                                      java.util.function.Consumer<UploadProgress> progress) throws IOException {
        return upload(file, chatSessionId, progress, new UploadCheckpoint() {});
    }

    Map<String, Object> upload(MultipartFile file, String chatSessionId,
                              java.util.function.Consumer<UploadProgress> progress, UploadCheckpoint checkpoint) throws IOException {
        String ownerId = SecurityUtil.getCurrentUserId();
        if (file.isEmpty()) throw new BizException(400, "文档不能为空");
        if (file.getSize() > 100L * 1024 * 1024) throw new BizException(413, "文件大小不能超过 100MB");
        if (chatSessionId != null && !chatSessionId.isBlank()) {
            var existing = chatSessionMapper.selectById(chatSessionId);
            if (existing == null || !ownerId.equals(existing.getUserId())) throw BizException.notFound("会话不存在");
        }
        pruneExpired();
        long owned = listSessions().size();
        if ((chatSessionId == null || !sessionStore.listIds(ownerId).contains(chatSessionId)) && owned >= 10) {
            throw new BizException(429, "临时文档会话已达上限，请删除旧会话后重试");
        }
        String sessionId = (chatSessionId != null && !chatSessionId.isBlank())
                ? chatSessionId
                : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String fileName = file.getOriginalFilename();
        String ext = fileName != null ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "txt";
        if (!List.of("pdf", "docx", "xlsx", "csv", "txt", "md").contains(ext)) throw new BizException(400, "不支持的文档格式");

        // 1. Extract text
        long parseStarted = System.nanoTime();
        progress.accept(new UploadProgress("PARSING", 0, 0));
        var source = checkpoint.source();
        boolean reusedParse = source != null;
        if (source == null) source = sourceParser.parse(file.getBytes(), fileName, new SourceDocumentParser.ProgressListener() {
            private int nativePages, enhancedPages;
            public void onPdfPages(int total) { nativePages = total; progress.accept(new UploadProgress("PARSING_NATIVE", 0, total)); }
            public void onPdfPage(int done) { progress.accept(new UploadProgress("PARSING_NATIVE", done, nativePages)); }
            public void onEnhancedPages(int total) { enhancedPages = total; progress.accept(new UploadProgress("PARSING_ENHANCED", 0, total)); }
            public void onEnhancedPage(int done) { progress.accept(new UploadProgress("PARSING_ENHANCED", done, enhancedPages)); }
        });
        checkpoint.saveSource(source);
        long parseMs = (System.nanoTime() - parseStarted) / 1_000_000;
        String text = source.text();
        if (text.isBlank()) throw new IllegalArgumentException("无法从文件中提取文本内容");
        int textLimit = ext.equals("csv") ? 2_000_000 : 500_000;
        if (text.length() > textLimit) throw new BizException(400, ext.equals("csv") ? "CSV 已解析内容不能超过 200 万字符" : "文档内容不能超过 50 万字");

        // 2. Chunk
        var locations = sourceParser.chunks(source, 500, 100);
        List<String> chunks = locations.stream().map(SourceDocumentParser.LocatedChunk::content).toList();

        // 3. Embed bounded batches, publishing progress only after a batch is validated.
        progress.accept(new UploadProgress("EMBEDDING", 0, chunks.size()));
        var embedded = DocumentEmbeddingBatcher.embed(embeddingModel, locations,
                done -> progress.accept(new UploadProgress("EMBEDDING", done, chunks.size())),
                checkpoint.embeddings(), checkpoint::saveEmbeddings);
        List<ChunkEmbedding> chunkEmbeddings = embedded.embeddings();

        // 4. Create session
        DocSession session = new DocSession(sessionId, ownerId, fileName, chunks, chunkEmbeddings,
                System.currentTimeMillis(), System.currentTimeMillis(), source, locations, checkpoint.sourceVersion());
        progress.accept(new UploadProgress("SAVING", chunks.size(), chunks.size()));
        checkpoint.publish(() -> {
            if (chatSessionId != null && !chatSessionId.isBlank()) {
                var existing = chatSessionMapper.selectById(chatSessionId);
                if (existing == null || !ownerId.equals(existing.getUserId())) throw BizException.notFound("会话已被删除");
            }
            sessionStore.save(session);
            sessions.put(sessionId, session);
        });

        log.info("Document QA session {} created: {} chunks from {}", sessionId, chunks.size(), fileName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("fileName", fileName);
        result.put("chunkCount", chunks.size());
        result.put("sourceVersion", session.sourceVersion);
        result.put("parseReport", source.parseReport());
        result.put("parseMs", parseMs);
        result.put("reusedParse", reusedParse);
        result.put("embeddingStats", embedded.stats());
        return result;
    }

    /** List active sessions. */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        String userId = SecurityUtil.getCurrentUserId();
        for (String id : sessionStore.listIds(userId)) {
            DocSession s = getSession(id);
            if (s == null) {
                sessionStore.delete(id, userId);
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.sessionId);
            m.put("fileName", s.fileName);
            m.put("chunkCount", s.chunks.size());
            m.put("messageCount", chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, s.sessionId)));
            m.put("createdAt", s.createdAt);
            list.add(m);
        }
        return list;
    }

    /** Delete a session. */
    public void deleteSession(String sessionId) {
        DocSession session = getSessionOrThrow(sessionId);
        if (session.busy.get()) throw new BizException(409, "当前文档会话正在回答");
        removeSession(sessionId, session.ownerId);
    }

    public void removeSession(String sessionId, String ownerId) {
        sessions.remove(sessionId);
        sessionStore.delete(sessionId, ownerId);
    }

    public Map<String, Object> sessionInfo(String sessionId) {
        DocSession s = getSessionOrThrow(sessionId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("sessionId",s.sessionId);result.put("fileName",s.fileName);result.put("chunkCount",s.chunks.size());
        result.put("sourceVersion",s.sourceVersion);
        result.put("parseReport",s.source == null ? null : s.source.parseReport());
        return result;
    }

    /** Get a session by ID. */
    public DocSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        DocSession s = sessions.get(sessionId);
        String ownerId = SecurityUtil.getCurrentUserId();
        if (s != null && (s.isExpired() || !ownerId.equals(s.ownerId))) return null;
        if (s == null) {
            DocumentQaSessionStore.StoredSession stored = sessionStore.load(sessionId);
            if (stored == null || !ownerId.equals(stored.ownerId())) return null;
            var chatSession = chatSessionMapper.selectById(sessionId);
            if (chatSession == null || !ownerId.equals(chatSession.getUserId())) return null;
            List<ChunkEmbedding> embeddings = stored.chunks().stream()
                    .map(c -> new ChunkEmbedding(c.text(), Embedding.from(c.vector()))).toList();
            List<String> chunks = stored.chunks().stream().map(DocumentQaSessionStore.StoredChunk::text).toList();
            s = new DocSession(sessionId, ownerId, stored.fileName(), chunks, embeddings,
                    stored.createdAt(), stored.lastAccess(), stored.source(), stored.locations(), stored.sourceVersion());
            List<ChatMessage> previous = new ArrayList<>(chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, sessionId)
                    .in(ChatMessage::getRole, "user", "assistant")
                    .ge(ChatMessage::getCreatedAt, java.time.LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(stored.createdAt()), java.time.ZoneId.systemDefault()))
                    .orderByDesc(ChatMessage::getCreatedAt).last("LIMIT 8")));
            Collections.reverse(previous);
            for (ChatMessage message : previous) {
                if ("user".equals(message.getRole())) {
                    s.history.add(dev.langchain4j.data.message.UserMessage.from(message.getContent()));
                } else {
                    s.history.add(dev.langchain4j.data.message.AiMessage.from(message.getContent()));
                }
            }
            DocSession prior = sessions.putIfAbsent(sessionId, s);
            if (prior != null) s = prior;
        }
        s.touch();
        sessionStore.save(s);
        return s;
    }

    public DocSession getSessionOrThrow(String sessionId) {
        DocSession session = getSession(sessionId);
        if (session == null) throw BizException.notFound("会话不存在或已过期");
        return session;
    }

    public SourceDocumentParser.SourceView sourceView(String sessionId, int ordinal, String version) {
        DocSession session = getSessionOrThrow(sessionId);
        if (version != null && !version.equals(session.sourceVersion)) throw BizException.notFound("原文已被替换或已过期");
        if (ordinal < 1 || ordinal > session.chunks.size()) throw BizException.notFound("文档片段不存在");
        var location = session.locations.size() >= ordinal ? session.locations.get(ordinal - 1) : null;
        return SourceDocumentParser.view(null, session.fileName, ordinal, session.source,
                location == null ? null : location.start(), location == null ? null : location.end(),
                session.chunks.get(ordinal - 1));
    }

    @Scheduled(fixedDelay = 60_000L)
    public void pruneExpired() { sessions.entrySet().removeIf(e -> e.getValue().isExpired()); }

    public void removeAllForUser(String userId) {
        sessions.entrySet().removeIf(e -> userId.equals(e.getValue().ownerId));
        for (String id : sessionStore.listIds(userId)) sessionStore.delete(id, userId);
    }

    // ── Session model ──

    public static class ChunkEmbedding {
        public final String text;
        public final Embedding embedding;
        public ChunkEmbedding(String text, Embedding embedding) { this.text = text; this.embedding = embedding; }
    }

    public static class DocSession {
        public final String sessionId;
        public final String ownerId;
        public final String fileName;
        public final List<String> chunks;
        public final List<ChunkEmbedding> chunkEmbeddings;
        public final SourceDocumentParser.SourceDocument source;
        public final List<SourceDocumentParser.LocatedChunk> locations;
        public final String sourceVersion;
        public final List<dev.langchain4j.data.message.ChatMessage> history = new ArrayList<>();
        public final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
        public final long createdAt;
        private long lastAccess;

        public DocSession(String id, String ownerId, String name, List<String> chunks, List<ChunkEmbedding> chunkEmbeddings) {
            this(id, ownerId, name, chunks, chunkEmbeddings, System.currentTimeMillis(), System.currentTimeMillis());
        }

        public DocSession(String id, String ownerId, String name, List<String> chunks,
                          List<ChunkEmbedding> chunkEmbeddings, long createdAt, long lastAccess) {
            this(id, ownerId, name, chunks, chunkEmbeddings, createdAt, lastAccess, null, List.of(), null);
        }

        public DocSession(String id, String ownerId, String name, List<String> chunks,
                          List<ChunkEmbedding> chunkEmbeddings, long createdAt, long lastAccess,
                          SourceDocumentParser.SourceDocument source, List<SourceDocumentParser.LocatedChunk> locations, String sourceVersion) {
            this.sessionId = id; this.ownerId = ownerId; this.fileName = name; this.chunks = chunks; this.chunkEmbeddings = chunkEmbeddings;
            this.createdAt = createdAt; this.lastAccess = lastAccess;
            this.source = source;
            this.locations = locations == null ? List.of() : locations;
            this.sourceVersion = sourceVersion;
        }

        public void touch() { lastAccess = System.currentTimeMillis(); }
        public long lastAccess() { return lastAccess; }
        public boolean isExpired() { return System.currentTimeMillis() - lastAccess > 30 * 60 * 1000; }

        /** Brute-force cosine similarity search. Returns top-K results with chunk indices. */
        public List<SearchHit> search(Embedding query, int topK, double minScore) {
            List<SearchHit> hits = new ArrayList<>();
            for (int i = 0; i < chunkEmbeddings.size(); i++) {
                ChunkEmbedding ce = chunkEmbeddings.get(i);
                double score = cosineSimilarity(query.vector(), ce.embedding.vector());
                if (score >= minScore) hits.add(new SearchHit(ce.text, score, i + 1)); // 1-based ordinal
            }
            hits.sort((a, b) -> Double.compare(b.score, a.score));
            return hits.size() > topK ? hits.subList(0, topK) : hits;
        }
    }

    public record SearchHit(String text, double score, int chunkIndex) {}

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
