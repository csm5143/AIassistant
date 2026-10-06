package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import com.aiproject.aiassitant.module.knowledge.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Commits the complete MySQL chunk set and the transition to VECTORIZING together.
 * A failed batch or lost PROCESSING claim rolls the entire document's new chunks back.
 */
@Service
@RequiredArgsConstructor
public class ChunkBatchWriter {
    private static final int BATCH_SIZE = 100;

    private final KbChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;

    @Transactional
    public void saveAndMarkVectorizing(String documentId, List<KbChunk> rows, long chunkStartNanos) {
        if (documentId == null || documentId.isBlank() || rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("文档分块不能为空");
        }
        for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
            List<KbChunk> batch = rows.subList(start, Math.min(start + BATCH_SIZE, rows.size()));
            int inserted = chunkMapper.insertBatch(batch);
            if (inserted != batch.size()) {
                throw new IllegalStateException("分块批量写入数量不完整");
            }
        }
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "PROCESSING")
                .set(KbDocument::getStatus, "VECTORIZING")
                .set(KbDocument::getProgressStage, "EMBEDDING")
                .set(KbDocument::getChunkCount, rows.size())
                .set(KbDocument::getVectorizedCount, 0)
                .set(KbDocument::getChunkDurationMs,
                        Math.max(0L, (System.nanoTime() - chunkStartNanos) / 1_000_000L)));
        if (updated != 1) {
            throw new IllegalStateException("文档处理状态已改变，分块写入已回滚");
        }
    }
}
