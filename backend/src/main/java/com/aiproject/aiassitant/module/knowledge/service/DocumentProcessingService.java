package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import com.aiproject.aiassitant.module.knowledge.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * kb_document is the durable queue. The executor holds only two running jobs;
 * a restart can discover waiting rows and resume an interrupted vector phase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {
    private final SourceDocumentParser sourceParser;
    private final KnowledgeStorageService storage;
    private final KnowledgeIndexVersion indexVersion;
    private final SourceSnapshotService sourceSnapshots;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final VectorSearchService vectorSearchService;
    private final ChunkBatchWriter chunkBatchWriter;
    private final ThreadPoolTaskExecutor documentProcessor;

    private final Semaphore workerSlots = new Semaphore(2);
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dispatching = new AtomicBoolean();

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedDocuments() {
        // Only this process owns workers. A partial PARSING/CHUNKING pass must be
        // rebuilt; a completed chunk set in VECTORIZING can be resumed below.
        documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getStatus, "PROCESSING")
                .set(KbDocument::getStatus, "PENDING")
                .set(KbDocument::getProgressStage, "QUEUED")
                .set(KbDocument::getProcessedPages, 0)
                .set(KbDocument::getTotalPages, 0)
                .set(KbDocument::getVectorizedCount, 0)
                .set(KbDocument::getChunkCount, 0)
                .set(KbDocument::getParseReportJson, null)
                .set(KbDocument::getParseDurationMs, 0)
                .set(KbDocument::getChunkDurationMs, 0)
                .set(KbDocument::getVectorDurationMs, 0));
        log.info("Recovered pending document queue");
    }

    /**
     * Called after storagePath has been saved. Keeping PENDING in the database
     * is sufficient; the scheduler will claim it without a volatile handoff.
     */
    public void processDocument(String documentId) {
        if (documentId == null) return;
        KbDocument doc = documentMapper.selectById(documentId);
        if (doc == null) return;
        if (doc.getStoragePath() == null || doc.getStoragePath().isBlank()) {
            if ("FAILED".equals(doc.getStatus())) {
                throw new com.aiproject.aiassitant.common.BizException(409, "原文件未保存，请重新上传");
            }
            return;
        }
        if ("FAILED".equals(doc.getStatus())) {
            documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "FAILED")
                    .set(KbDocument::getStatus, "PENDING").set(KbDocument::getProgressStage, "QUEUED")
                    .set(KbDocument::getErrorMessage, null)
                    .set(KbDocument::getProcessedPages, 0).set(KbDocument::getTotalPages, 0)
                    .set(KbDocument::getVectorizedCount, 0).set(KbDocument::getChunkCount, 0)
                    .set(KbDocument::getParseDurationMs, 0).set(KbDocument::getChunkDurationMs, 0)
                    .set(KbDocument::getVectorDurationMs, 0));
        } else if ("VECTOR_FAILED".equals(doc.getStatus())) {
            // The prior MySQL chunks and committed PG batches remain available.
            documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "VECTOR_FAILED")
                    .set(KbDocument::getStatus, "VECTORIZING").set(KbDocument::getProgressStage, "EMBEDDING")
                    .set(KbDocument::getVectorizedCount, 0)
                    .set(KbDocument::getErrorMessage, null));
        }
    }

    /** A file row abandoned before storage completed must not wait in the queue forever. */
    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void expireUnstoredUploads() {
        int expired = documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getStatus, "PENDING")
                .lt(KbDocument::getCreatedAt, LocalDateTime.now().minusMinutes(15))
                .and(w -> w.isNull(KbDocument::getStoragePath).or().eq(KbDocument::getStoragePath, ""))
                .set(KbDocument::getStatus, "FAILED")
                .set(KbDocument::getProgressStage, "FAILED")
                .set(KbDocument::getErrorMessage, "文件保存未完成，请重新上传"));
        if (expired > 0) log.warn("Expired {} unfinished document uploads", expired);
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 1000)
    public void dispatchPending() {
        if (!dispatching.compareAndSet(false, true)) return;
        try {
            if (workerSlots.availablePermits() == 0) return;
            List<KbDocument> candidates = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                    .in(KbDocument::getStatus, "PENDING", "VECTORIZING")
                    .isNotNull(KbDocument::getStoragePath)
                    .ne(KbDocument::getStoragePath, "")
                    .orderByAsc(KbDocument::getCreatedAt)
                    .last("LIMIT 100"));
            for (KbDocument candidate : candidates) {
                if (!workerSlots.tryAcquire()) break;
                if (!inFlight.add(candidate.getId())) {
                    workerSlots.release();
                    continue;
                }
                try {
                    documentProcessor.execute(() -> {
                        try { runDocument(candidate.getId()); }
                        finally {
                            inFlight.remove(candidate.getId());
                            workerSlots.release();
                        }
                    });
                } catch (org.springframework.core.task.TaskRejectedException e) {
                    inFlight.remove(candidate.getId());
                    workerSlots.release();
                    log.debug("Document workers busy; {} remains safely queued", candidate.getId());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Document queue scan failed; will retry: {}", e.getMessage());
        } finally {
            dispatching.set(false);
        }
    }

    private void runDocument(String documentId) {
        KbDocument document = documentMapper.selectById(documentId);
        if (document == null) return;
        boolean resuming = "VECTORIZING".equals(document.getStatus());
        if (!resuming) {
            int claimed = documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "PENDING")
                    .isNotNull(KbDocument::getStoragePath).ne(KbDocument::getStoragePath, "")
                    .set(KbDocument::getStatus, "PROCESSING")
                    .set(KbDocument::getProgressStage, "PARSING")
                    .set(KbDocument::getErrorMessage, null)
                    .set(KbDocument::getProcessedPages, 0)
                    .set(KbDocument::getTotalPages, 0)
                    .set(KbDocument::getVectorizedCount, 0)
                    .set(KbDocument::getChunkCount, 0)
                    .set(KbDocument::getParseDurationMs, 0)
                    .set(KbDocument::getChunkDurationMs, 0)
                    .set(KbDocument::getVectorDurationMs, 0));
            if (claimed == 0) return;
        }
        document = documentMapper.selectById(documentId);
        indexVersion.changed(document.getUserId());
        boolean vectorizing = resuming;
        try {
            if (resuming && resumable(document)) {
                vectorize(document, true);
                return;
            }
            if (resuming) {
                // Invalid/incomplete checkpoint is hidden from retrieval and rebuilt.
                documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                        .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "VECTORIZING")
                        .set(KbDocument::getStatus, "PROCESSING")
                        .set(KbDocument::getProgressStage, "PARSING")
                        .set(KbDocument::getChunkCount, 0)
                        .set(KbDocument::getVectorizedCount, 0)
                        .set(KbDocument::getProcessedPages, 0)
                        .set(KbDocument::getTotalPages, 0)
                        .set(KbDocument::getParseDurationMs, 0)
                        .set(KbDocument::getChunkDurationMs, 0)
                        .set(KbDocument::getVectorDurationMs, 0));
                vectorizing = false;
            }
            processFresh(documentMapper.selectById(documentId));
            vectorizing = true;
            vectorize(documentMapper.selectById(documentId), false);
        } catch (Exception e) {
            log.error("Failed to process document: {}", documentId, e);
            String failedStatus = vectorizing || "VECTORIZING".equals(statusOf(documentId))
                    ? "VECTOR_FAILED" : "FAILED";
            documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, documentId)
                    .in(KbDocument::getStatus, "PROCESSING", "VECTORIZING")
                    .set(KbDocument::getStatus, failedStatus)
                    .set(KbDocument::getProgressStage, "FAILED")
                    .set(KbDocument::getErrorMessage, "VECTOR_FAILED".equals(failedStatus)
                            ? "向量生成失败，请检查嵌入服务后重试"
                            : e instanceof com.aiproject.aiassitant.common.BizException ? e.getMessage()
                            : "FOLDER".equals(document.getSourceKind()) ? "绑定文件处理失败，请检查源文件并再次同步"
                            : "文档处理失败，请检查文件格式后重试"));
        } finally {
            indexVersion.changed(document.getUserId());
        }
    }

    private String statusOf(String documentId) {
        KbDocument doc = documentMapper.selectById(documentId);
        return doc == null ? "" : doc.getStatus();
    }

    private boolean resumable(KbDocument document) {
        int expected = document.getChunkCount() == null ? 0 : document.getChunkCount();
        if (expected <= 0) return false;
        if ("FOLDER".equals(document.getSourceKind())) {
            try { storage.previewFile(document); }
            catch (Exception changedSource) {
                log.warn("Bound source for {} changed while indexing; reparsing requires a folder sync", document.getId());
                return false;
            }
        }
        SourceDocumentParser.SourceDocument source;
        try { source = sourceSnapshots.load(document.getId()); }
        catch (RuntimeException corruptSnapshot) {
            log.warn("Source checkpoint for {} is unusable; reparsing", document.getId());
            return false;
        }
        if (source == null || source.text() == null || source.text().isBlank()) return false;
        List<KbChunk> rows = chunksOf(document.getId());
        if (rows.size() != expected) return false;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            KbChunk chunk = rows.get(i);
            if (chunk.getId() == null || chunk.getOrdinal() == null || chunk.getOrdinal() != i + 1 ||
                    !document.getId().equals(chunk.getDocumentId()) ||
                    !document.getUserId().equals(chunk.getUserId()) ||
                    !document.getCollectionId().equals(chunk.getCollectionId()) ||
                    chunk.getContent() == null || chunk.getContent().isBlank() ||
                    chunk.getSourceStart() == null || chunk.getSourceEnd() == null ||
                    chunk.getSourceStart() < 0 || chunk.getSourceEnd() > source.text().length() ||
                    chunk.getSourceStart() >= chunk.getSourceEnd() ||
                    !source.text().substring(chunk.getSourceStart(), chunk.getSourceEnd()).equals(chunk.getContent()) ||
                    !ids.add(chunk.getId())) return false;
        }
        return ids.containsAll(vectorSearchService.existingVectorChunkIds(document.getId()));
    }

    private void processFresh(KbDocument document) throws Exception {
        String documentId = document.getId();
        vectorSearchService.deleteVectorsByDocument(documentId);
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));

        long parseStart = System.nanoTime();
        String filename = document.getFilename() == null ? "" : document.getFilename().toLowerCase();
        byte[] fileBytes = storage.readBytes(document);
        var source = sourceParser.parse(fileBytes, filename, new SourceDocumentParser.ProgressListener() {
            private int lastReported;
            private int pageTotal;
            @Override public void onPdfPages(int total) {
                pageTotal = total;
                documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                        .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "PROCESSING")
                        .set(KbDocument::getTotalPages, total).set(KbDocument::getProcessedPages, 0));
            }
            @Override public void onPdfPage(int done) {
                if (done - lastReported < 25 && done < pageTotal) return;
                lastReported = done;
                documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                        .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "PROCESSING")
                        .set(KbDocument::getProcessedPages, done));
            }
            @Override public void onEnhancedPages(int total) {
                documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                        .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "PROCESSING")
                        .set(KbDocument::getProgressStage, "ENHANCING")
                        .set(KbDocument::getTotalPages, total).set(KbDocument::getProcessedPages, 0));
            }
            @Override public void onEnhancedPage(int done) {
                documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                        .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "PROCESSING")
                        .set(KbDocument::getProcessedPages, done));
            }
        });
        if (source.text().isBlank()) throw new IllegalStateException("文档无可提取文本，扫描件需先 OCR");
        sourceSnapshots.save(documentId, source);
        long parseMs = elapsedMs(parseStart);
        documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "PROCESSING")
                .set(KbDocument::getProgressStage, "CHUNKING")
                .set(KbDocument::getParseDurationMs, parseMs)
                .set(KbDocument::getParseReportJson, source.parseReport() == null ? null :
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(source.parseReport()))
                .set(KbDocument::getRoutingKeywords, RoutingTerms.profile(document.getFilename(), source.text())));

        long chunkStart = System.nanoTime();
        var chunks = sourceParser.chunks(source,
                document.getChunkSize() != null ? document.getChunkSize() : 500,
                document.getChunkOverlap() != null ? document.getChunkOverlap() : 80);
        String userId = document.getUserId();
        if (userId == null || userId.isBlank()) throw new IllegalStateException("文档缺少所属用户");
        if (chunks.isEmpty()) throw new IllegalStateException("文档未产生有效片段");
        List<KbChunk> chunkRows = new ArrayList<>(chunks.size());
        LocalDateTime createdAt = LocalDateTime.now();
        int ordinal = 1;
        for (var located : chunks) {
            String chunkContent = located.content();
            KbChunk chunk = new KbChunk();
            chunk.setId(UUID.randomUUID().toString().replace("-", ""));
            chunk.setDocumentId(documentId);
            chunk.setCollectionId(document.getCollectionId());
            chunk.setUserId(userId);
            chunk.setOrdinal(ordinal++);
            chunk.setContent(chunkContent);
            chunk.setSourceStart(located.start());
            chunk.setSourceEnd(located.end());
            chunk.setSourcePage(located.page());
            chunk.setSourceTitle(limitTitle(located.title()));
            chunk.setTokenCount((int) Math.ceil(chunkContent.length() / 4.0));
            chunk.setCreatedAt(createdAt);
            chunkRows.add(chunk);
        }
        chunkBatchWriter.saveAndMarkVectorizing(documentId, chunkRows, chunkStart);
    }

    private static String limitTitle(String title) {
        if (title == null) return null;
        int count = title.codePointCount(0, title.length());
        return count <= 512 ? title : title.substring(0, title.offsetByCodePoints(0, 512));
    }

    private List<KbChunk> chunksOf(String documentId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId).orderByAsc(KbChunk::getOrdinal));
    }

    private void vectorize(KbDocument document, boolean resumed) {
        String documentId = document.getId();
        List<KbChunk> chunks = chunksOf(documentId);
        if (chunks.isEmpty() || !Integer.valueOf(chunks.size()).equals(document.getChunkCount())) {
            throw new IllegalStateException("文档分块与进度不一致");
        }
        Set<String> existing = resumed ? vectorSearchService.existingVectorChunkIds(documentId) : Set.of();
        Set<String> chunkIds = new HashSet<>();
        for (KbChunk chunk : chunks) chunkIds.add(chunk.getId());
        if (!chunkIds.containsAll(existing)) throw new IllegalStateException("向量分块与文档不一致");
        AtomicInteger done = new AtomicInteger(existing.size());
        int priorDuration = document.getVectorDurationMs() == null ? 0 :
                (int) Math.min(Integer.MAX_VALUE, document.getVectorDurationMs());
        long vectorStart = System.nanoTime();
        documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "VECTORIZING")
                .set(KbDocument::getProgressStage, "EMBEDDING")
                .set(KbDocument::getVectorizedCount, done.get()));
        List<VectorSearchService.VectorInput> missing = chunks.stream()
                .filter(chunk -> !existing.contains(chunk.getId()))
                .map(chunk -> new VectorSearchService.VectorInput(chunk.getId(),
                        SourceDocumentParser.retrievalText(
                                chunk.getContent(), chunk.getSourcePage(), chunk.getSourceTitle())))
                .toList();
        vectorSearchService.storeVectors(document.getCollectionId(), documentId, document.getUserId(),
                missing, committed -> documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                        .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "VECTORIZING")
                        .set(KbDocument::getVectorizedCount, done.addAndGet(committed))
                        .set(KbDocument::getVectorDurationMs, priorDuration + elapsedMs(vectorStart))));
        Set<String> after = vectorSearchService.existingVectorChunkIds(documentId);
        if (!after.equals(chunkIds) || done.get() != chunks.size()) {
            throw new IllegalStateException("向量数量不完整，禁止标记完成");
        }
        documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "VECTORIZING")
                .set(KbDocument::getStatus, "READY")
                .set(KbDocument::getProgressStage, "COMPLETE")
                .set(KbDocument::getVectorizedCount, chunks.size())
                .set(KbDocument::getVectorDurationMs, priorDuration + elapsedMs(vectorStart))
                .set(KbDocument::getErrorMessage, null));
        log.info("Document processed: {} ({} chunks, {} resumed)", documentId, chunks.size(), existing.size());
    }

    /** Legacy upload callers may still invoke this on an executor rejection. */
    public void markQueueFailure(String documentId) {
        log.warn("Document {} remains queued; durable queue will retry", documentId);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
