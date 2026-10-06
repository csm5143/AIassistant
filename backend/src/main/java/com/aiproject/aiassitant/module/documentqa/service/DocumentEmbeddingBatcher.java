package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Bounded batches preserve chunk/vector order; no session is published before every batch succeeds. */
final class DocumentEmbeddingBatcher {
    static final int BATCH_SIZE = 32;
    record Stats(int batchCalls, int embeddedChunks, int promptTokens, boolean tokenUsageComplete, long elapsedMs,
                 int reusedChunks, int totalBatchCalls) {}
    record Result(List<DocumentQaService.ChunkEmbedding> embeddings, Stats stats) {}

    static Result embed(EmbeddingModel model, List<SourceDocumentParser.LocatedChunk> chunks, IntConsumer progress) {
        return embed(model, chunks, progress, null, saved -> {});
    }

    static Result embed(EmbeddingModel model, List<SourceDocumentParser.LocatedChunk> chunks, IntConsumer progress,
                        Result previous, java.util.function.Consumer<Result> checkpoint) {
        long started = System.nanoTime();
        List<DocumentQaService.ChunkEmbedding> result = new ArrayList<>();
        int dimension = -1, calls = 0, tokens = previous == null ? 0 : previous.stats().promptTokens();
        boolean usageComplete = previous == null || previous.stats().tokenUsageComplete();
        int priorCalls = previous == null ? 0 : previous.stats().totalBatchCalls();
        if (previous != null) {
            if (previous.embeddings().size() > chunks.size() || previous.embeddings().size() != chunks.size()
                    && previous.embeddings().size() % BATCH_SIZE != 0) throw new IllegalStateException("恢复检查点的片段数量无效");
            for (int i=0;i<previous.embeddings().size();i++) {
                var saved=previous.embeddings().get(i);
                if(!saved.text.equals(chunks.get(i).content()))throw new IllegalStateException("恢复检查点与当前文档片段不一致");
                if(saved.embedding==null||saved.embedding.dimension()==0)throw new IllegalStateException("恢复检查点向量无效");
                if(dimension<0)dimension=saved.embedding.dimension();
                if(saved.embedding.dimension()!=dimension)throw new IllegalStateException("恢复检查点向量维度不一致");
                for(float value:saved.embedding.vector())if(!Float.isFinite(value))throw new IllegalStateException("恢复检查点包含非有限向量");
                result.add(saved);
            }
        }
        int reused = result.size();
        if(reused>0)progress.accept(reused);
        for (int start = result.size(); start < chunks.size(); start += BATCH_SIZE) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var batch = chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size()));
            List<TextSegment> segments = batch.stream().map(chunk -> TextSegment.from(
                    SourceDocumentParser.retrievalText(chunk.content(), chunk.page(), chunk.title()))).toList();
            calls++;
            var response = model.embedAll(segments);
            List<Embedding> vectors = response == null ? null : response.content();
            if (vectors == null || vectors.size() != batch.size()) throw new IllegalStateException("嵌入向量数量与文档片段数量不一致");
            for (int i = 0; i < vectors.size(); i++) {
                Embedding vector = vectors.get(i);
                if (vector == null || vector.dimension() == 0) throw new IllegalStateException("嵌入服务返回空向量");
                if (dimension < 0) dimension = vector.dimension();
                if (vector.dimension() != dimension) throw new IllegalStateException("嵌入向量维度不一致");
                for (float value : vector.vector()) if (!Float.isFinite(value)) throw new IllegalStateException("嵌入向量包含无效数值");
                result.add(new DocumentQaService.ChunkEmbedding(batch.get(i).content(), vector));
            }
            var usage = response.tokenUsage();
            if (usage == null || usage.inputTokenCount() == null) usageComplete = false;
            else tokens += usage.inputTokenCount();
            checkpoint.accept(new Result(List.copyOf(result), new Stats(calls, result.size(), tokens, usageComplete,
                    (System.nanoTime()-started)/1_000_000,reused,priorCalls+calls)));
            progress.accept(result.size());
        }
        return new Result(List.copyOf(result), new Stats(calls, chunks.size(), tokens, usageComplete,
                (System.nanoTime() - started) / 1_000_000, reused, priorCalls + calls));
    }
}
