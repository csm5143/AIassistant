package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import com.aiproject.aiassitant.module.knowledge.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentProcessingQueueTest {
    private final SourceDocumentParser parser = mock(SourceDocumentParser.class);
    private final KnowledgeStorageService storage = mock(KnowledgeStorageService.class);
    private final KnowledgeIndexVersion version = mock(KnowledgeIndexVersion.class);
    private final SourceSnapshotService snapshots = mock(SourceSnapshotService.class);
    private final KbDocumentMapper documents = mock(KbDocumentMapper.class);
    private final KbChunkMapper chunks = mock(KbChunkMapper.class);
    private final VectorSearchService vectors = mock(VectorSearchService.class);
    private final ChunkBatchWriter chunkBatchWriter = mock(ChunkBatchWriter.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private DocumentProcessingService service;

    @BeforeAll static void tableMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "document-queue-test");
        TableInfoHelper.initTableInfo(assistant, KbDocument.class);
        TableInfoHelper.initTableInfo(assistant, KbChunk.class);
    }

    @BeforeEach void setup() {
        service = new DocumentProcessingService(parser, storage, version, snapshots,
                documents, chunks, vectors, chunkBatchWriter, executor);
    }

    @Test void startupRecoversParsingButKeepsVectorCheckpoint() {
        service.recoverInterruptedDocuments();
        @SuppressWarnings("unchecked")
        var update = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documents).update(isNull(), update.capture());
        update.getValue().getSqlSegment();
        var values = update.getValue().getParamNameValuePairs().values();
        assertTrue(values.contains("PROCESSING"));
        assertTrue(values.contains("PENDING"));
        assertFalse(values.contains("VECTORIZING"));
    }

    @Test void failedUploadWithoutStoredFileCannotClaimAQueuedRetry() {
        KbDocument doc = document("d", "FAILED");
        doc.setStoragePath(null);
        when(documents.selectById("d")).thenReturn(doc);

        var error = assertThrows(com.aiproject.aiassitant.common.BizException.class,
                () -> service.processDocument("d"));
        assertEquals(409, error.getCode());
        assertEquals("原文件未保存，请重新上传", error.getMessage());
        verify(documents, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test void expiredUnstoredUploadOnlyTargetsOldPendingRows() {
        service.expireUnstoredUploads();
        @SuppressWarnings("unchecked")
        var update = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documents).update(isNull(), update.capture());
        String sql = update.getValue().getSqlSegment();
        var values = update.getValue().getParamNameValuePairs().values();
        assertTrue(values.contains("PENDING"));
        assertTrue(values.contains("FAILED"));
        assertTrue(sql.contains("storage_path"));
        assertTrue(values.stream().anyMatch(value -> value instanceof java.time.LocalDateTime cutoff
                && cutoff.isBefore(java.time.LocalDateTime.now().minusMinutes(14))));
    }

    @Test void processFreshPassesExactUnicodeLocationsAndCreationTimeToBatchWriter() throws Exception {
        KbDocument doc = document("d", "PROCESSING");
        doc.setFilename("doc.txt");
        String content = "中文🙂 English";
        String title = "标".repeat(511) + "🙂" + "尾";
        var source = new SourceDocumentParser.SourceDocument(content,
                List.of(new SourceDocumentParser.Block(0, content.length(), 1, title)));
        when(storage.readBytes(doc)).thenReturn(new byte[]{1, 2});
        when(parser.parse(any(byte[].class), eq("doc.txt"), any(SourceDocumentParser.ProgressListener.class)))
                .thenReturn(source);
        when(parser.chunks(source, 500, 80)).thenReturn(List.of(
                new SourceDocumentParser.LocatedChunk(content, 0, content.length(), 1, title)));

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "processFresh", doc));

        @SuppressWarnings("unchecked")
        var batches = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chunkBatchWriter).saveAndMarkVectorizing(eq("d"), batches.capture(), anyLong());
        KbChunk row = (KbChunk) batches.getValue().get(0);
        assertEquals(content, row.getContent());
        assertEquals(0, row.getSourceStart());
        assertEquals(content.length(), row.getSourceEnd());
        assertEquals(1, row.getSourcePage());
        assertEquals(1, row.getOrdinal());
        assertEquals("标".repeat(511) + "🙂", row.getSourceTitle());
        assertNotNull(row.getCreatedAt());
        verify(chunks, never()).insert(any(KbChunk.class));
    }

    @Test void durableQueueSubmitsOnlyTwoJobsAndDoesNotMarkWaitingJobsFailed() {
        KbDocument first = document("a", "PENDING");
        KbDocument second = document("b", "PENDING");
        KbDocument third = document("c", "PENDING");
        when(documents.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second, third));
        List<Runnable> scheduled = new CopyOnWriteArrayList<>();
        doAnswer(call -> { scheduled.add(call.getArgument(0)); return null; })
                .when(executor).execute(any(Runnable.class));

        service.dispatchPending();
        service.dispatchPending();

        assertEquals(2, scheduled.size());
        verify(documents, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test void resumeRequiresCompleteOwnedOrderedChunksAndNoForeignVectorIds() {
        KbDocument doc = document("d", "VECTORIZING");
        doc.setChunkCount(2);
        when(snapshots.load("d")).thenReturn(new SourceDocumentParser.SourceDocument("content acontent b", List.of()));
        when(chunks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk("a", 1), chunk("b", 2)));
        when(vectors.existingVectorChunkIds("d")).thenReturn(Set.of("a"));
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(service, "resumable", doc));

        when(vectors.existingVectorChunkIds("d")).thenReturn(Set.of("a", "old"));
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(service, "resumable", doc));

        when(chunks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk("a", 1)));
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(service, "resumable", doc));
    }

    @Test void changedBoundSourceCannotResumeAnOldCheckpoint() throws Exception {
        KbDocument doc = document("d", "VECTORIZING");
        doc.setSourceKind("FOLDER");
        doc.setChunkCount(1);
        when(storage.previewFile(doc)).thenThrow(new java.io.FileNotFoundException("changed"));
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(service, "resumable", doc));
        verify(snapshots, never()).load(anyString());
    }

    @Test void vectorResumeSkipsCommittedIdsAndChecksExactFinalSet() {
        KbDocument doc = document("d", "VECTORIZING");
        doc.setChunkCount(2);
        when(chunks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk("a", 1), chunk("b", 2)));
        when(vectors.existingVectorChunkIds("d")).thenReturn(Set.of("a"), Set.of("a", "b"));
        doAnswer(call -> {
            List<VectorSearchService.VectorInput> inputs = call.getArgument(3);
            assertEquals(List.of("b"), inputs.stream().map(VectorSearchService.VectorInput::chunkId).toList());
            IntConsumer progress = call.getArgument(4);
            progress.accept(inputs.size());
            return null;
        }).when(vectors).storeVectors(eq("kb"), eq("d"), eq("owner"), anyList(), any(IntConsumer.class));

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "vectorize", doc, true));
        @SuppressWarnings("unchecked")
        var update = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documents, atLeastOnce()).update(isNull(), update.capture());
        assertTrue(update.getAllValues().stream()
                .map(LambdaUpdateWrapper::getParamNameValuePairs)
                .map(Map::values)
                .anyMatch(values -> values.contains("READY")));
    }

    @Test void missingFinalVectorCannotBecomeReady() {
        KbDocument doc = document("d", "VECTORIZING");
        doc.setChunkCount(2);
        when(chunks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk("a", 1), chunk("b", 2)));
        when(vectors.existingVectorChunkIds("d")).thenReturn(Set.of("a"));
        doAnswer(call -> {
            IntConsumer progress = call.getArgument(4);
            progress.accept(1);
            return null;
        }).when(vectors).storeVectors(anyString(), anyString(), anyString(), anyList(), any(IntConsumer.class));

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "vectorize", doc, true));
        @SuppressWarnings("unchecked")
        var update = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documents, atLeastOnce()).update(isNull(), update.capture());
        assertTrue(update.getAllValues().stream().noneMatch(wrapper ->
                wrapper.getParamNameValuePairs().values().contains("READY")));
    }

    private KbDocument document(String id, String status) {
        KbDocument d = new KbDocument();
        d.setId(id); d.setStatus(status); d.setCollectionId("kb"); d.setUserId("owner");
        d.setStoragePath("D:/AIassistant-env/runtime/uploads/knowledge/" + id + "/doc.pdf");
        return d;
    }

    private KbChunk chunk(String id, int ordinal) {
        KbChunk c = new KbChunk();
        c.setId(id); c.setOrdinal(ordinal); c.setDocumentId("d");
        c.setCollectionId("kb"); c.setUserId("owner");
        c.setContent("content " + id);
        c.setSourceStart((ordinal - 1) * 9);
        c.setSourceEnd(ordinal * 9);
        c.setSourceTitle("title");
        return c;
    }
}
