package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentEmbeddingBatcherTest {
    private List<SourceDocumentParser.LocatedChunk> chunks(int count) {
        return IntStream.range(0, count).mapToObj(i -> new SourceDocumentParser.LocatedChunk("record-" + i, 0, 8, null, null)).toList();
    }
    @Test void batchesThirtyTwoAtATimeAndPreservesVectorOrderAndUsage() {
        var model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            List<TextSegment> segments = call.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(
                    new float[]{Integer.parseInt(segment.text().substring(7)), 1})).toList(), new TokenUsage(10, 0));
        });
        List<Integer> progress = new ArrayList<>();
        var result = DocumentEmbeddingBatcher.embed(model, chunks(33), progress::add);
        assertEquals(List.of(32,33), progress);
        assertEquals(2, result.stats().batchCalls());
        assertEquals(20, result.stats().promptTokens());
        assertTrue(result.stats().tokenUsageComplete());
        for (int i = 0; i < 33; i++) assertEquals(i, result.embeddings().get(i).embedding.vector()[0]);
        verify(model, times(2)).embedAll(anyList());
        verify(model, never()).embed(anyString());
    }
    @Test void failsWithoutPublishingProgressForMisalignedOrNonFiniteVectors() {
        var model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{1,2}))));
        List<Integer> progress = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> DocumentEmbeddingBatcher.embed(model, chunks(2), progress::add));
        assertTrue(progress.isEmpty());
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{Float.NaN,2}))));
        assertThrows(IllegalStateException.class, () -> DocumentEmbeddingBatcher.embed(model, chunks(1), progress::add));
        assertTrue(progress.isEmpty());
    }
    @Test void rejectsDimensionsThatChangeBetweenBatches() {
        var model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList())).thenReturn(
                Response.from(IntStream.range(0,32).mapToObj(i -> Embedding.from(new float[]{1,2})).toList()),
                Response.from(List.of(Embedding.from(new float[]{1,2,3}))));
        List<Integer> progress = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> DocumentEmbeddingBatcher.embed(model, chunks(33), progress::add));
        assertEquals(List.of(32), progress);
    }
    @Test void resumesFromValidatedBatchWithoutReembeddingSavedChunks() {
        var model=mock(EmbeddingModel.class);
        var saved=new DocumentEmbeddingBatcher.Result(IntStream.range(0,32).mapToObj(i->
                new DocumentQaService.ChunkEmbedding("record-"+i,Embedding.from(new float[]{i,1}))).toList(),
                new DocumentEmbeddingBatcher.Stats(1,32,10,true,100,0,1));
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{32,1})),new TokenUsage(5,0)));
        List<DocumentEmbeddingBatcher.Result> checkpoints=new ArrayList<>();
        var result=DocumentEmbeddingBatcher.embed(model,chunks(33),done->{},saved,checkpoints::add);
        assertEquals(32,result.stats().reusedChunks());assertEquals(1,result.stats().batchCalls());assertEquals(2,result.stats().totalBatchCalls());assertEquals(15,result.stats().promptTokens());
        assertEquals(33,checkpoints.get(0).embeddings().size());verify(model,times(1)).embedAll(anyList());
    }
    @Test void refusesMismatchedCheckpointBeforeAnyModelCall() {
        var model=mock(EmbeddingModel.class);var saved=new DocumentEmbeddingBatcher.Result(List.of(
                new DocumentQaService.ChunkEmbedding("wrong-source",Embedding.from(new float[]{1,2}))),
                new DocumentEmbeddingBatcher.Stats(1,1,10,true,100,0,1));
        assertThrows(IllegalStateException.class,()->DocumentEmbeddingBatcher.embed(model,chunks(1),done->{},saved,r->{}));verifyNoInteractions(model);
    }
}
