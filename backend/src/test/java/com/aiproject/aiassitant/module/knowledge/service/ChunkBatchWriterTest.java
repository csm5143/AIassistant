package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import com.aiproject.aiassitant.module.knowledge.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChunkBatchWriterTest {

    @BeforeAll static void tableMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "chunk-batch-test");
        TableInfoHelper.initTableInfo(assistant, KbDocument.class);
        TableInfoHelper.initTableInfo(assistant, KbChunk.class);
    }

    @Test void writesHundredsInOrderAndMarksVectorizingAfterAllBatches() {
        KbChunkMapper chunks = mock(KbChunkMapper.class);
        KbDocumentMapper documents = mock(KbDocumentMapper.class);
        when(chunks.insertBatch(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
        when(documents.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        List<KbChunk> rows = rows(201);

        new ChunkBatchWriter(chunks, documents).saveAndMarkVectorizing("document", rows, System.nanoTime());

        @SuppressWarnings("unchecked")
        var captured = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chunks, times(3)).insertBatch(captured.capture());
        assertEquals(List.of(100, 100, 1), captured.getAllValues().stream().map(List::size).toList());
        List<String> ids = captured.getAllValues().stream().flatMap(batch -> batch.stream())
                .map(row -> ((KbChunk) row).getId()).toList();
        assertEquals(rows.stream().map(KbChunk::getId).toList(), ids);
        assertEquals("中英混合🙂 0", ((KbChunk) captured.getAllValues().get(0).get(0)).getContent());
        assertEquals(0, ((KbChunk) captured.getAllValues().get(0).get(0)).getSourceStart());
        assertEquals("第 1 页", ((KbChunk) captured.getAllValues().get(0).get(0)).getSourceTitle());
        verify(documents).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test void failedSecondBatchNeverAdvancesStatus() {
        KbChunkMapper chunks = mock(KbChunkMapper.class);
        KbDocumentMapper documents = mock(KbDocumentMapper.class);
        when(chunks.insertBatch(anyList())).thenReturn(100).thenThrow(new IllegalStateException("insert failed"));

        assertThrows(IllegalStateException.class,
                () -> new ChunkBatchWriter(chunks, documents)
                        .saveAndMarkVectorizing("document", rows(101), System.nanoTime()));
        verify(chunks, times(2)).insertBatch(anyList());
        verifyNoInteractions(documents);
    }

    @Test void lostProcessingClaimThrowsToRollbackAllBatches() {
        KbChunkMapper chunks = mock(KbChunkMapper.class);
        KbDocumentMapper documents = mock(KbDocumentMapper.class);
        when(chunks.insertBatch(anyList())).thenReturn(1);
        when(documents.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> new ChunkBatchWriter(chunks, documents)
                        .saveAndMarkVectorizing("document", rows(1), System.nanoTime()));
    }

    @Test void mybatisUsesBoundParametersForUnicodeAndAllLocationFields() throws Exception {
        Insert insert = KbChunkMapper.class.getMethod("insertBatch", List.class).getAnnotation(Insert.class);
        var source = new XMLLanguageDriver().createSqlSource(
                new org.apache.ibatis.session.Configuration(), String.join(" ", insert.value()), Map.class);
        BoundSql bound = source.getBoundSql(Map.of("rows", rows(2)));

        assertEquals(24, bound.getParameterMappings().size());
        assertTrue(bound.getSql().contains("source_start"));
        assertTrue(bound.getSql().contains("source_end"));
        assertTrue(bound.getSql().contains("source_page"));
        assertTrue(bound.getSql().contains("source_title"));
        assertFalse(bound.getSql().contains("中英混合"));
    }

    private static List<KbChunk> rows(int count) {
        LocalDateTime createdAt = LocalDateTime.now();
        return IntStream.range(0, count).mapToObj(i -> {
            KbChunk chunk = new KbChunk();
            chunk.setId("id-" + i);
            chunk.setDocumentId("document");
            chunk.setCollectionId("collection");
            chunk.setUserId("owner");
            chunk.setOrdinal(i + 1);
            chunk.setContent("中英混合🙂 " + i);
            chunk.setTokenCount(4);
            chunk.setCreatedAt(createdAt);
            chunk.setSourceStart(i * 20);
            chunk.setSourceEnd(i * 20 + 7);
            chunk.setSourcePage(1);
            chunk.setSourceTitle("第 1 页");
            return chunk;
        }).toList();
    }
}
