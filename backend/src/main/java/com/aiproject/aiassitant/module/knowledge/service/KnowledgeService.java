package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.entity.KbCollection;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import com.aiproject.aiassitant.module.knowledge.mapper.KbCollectionMapper;
import com.aiproject.aiassitant.module.knowledge.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KbCollectionMapper collectionMapper;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final VectorSearchService vectorSearchService;
    private final HybridSearchService hybridSearchService;
    private final KnowledgeStorageService storage;
    private final KnowledgeIndexVersion indexVersion;

    public void queueReparse(String documentId) {
        KbDocument doc = getDocument(documentId);
        int changed = documentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, doc.getId()).eq(KbDocument::getUserId, SecurityUtil.getCurrentUserId())
                .in(KbDocument::getStatus, "READY", "FAILED", "VECTOR_FAILED")
                .set(KbDocument::getStatus,"PENDING").set(KbDocument::getErrorMessage,null)
                .set(KbDocument::getParseReportJson,null)
                .set(KbDocument::getProgressStage,"QUEUED")
                .set(KbDocument::getProcessedPages,0).set(KbDocument::getTotalPages,0)
                .set(KbDocument::getVectorizedCount,0).set(KbDocument::getChunkCount,0)
                .set(KbDocument::getParseDurationMs,0).set(KbDocument::getChunkDurationMs,0)
                .set(KbDocument::getVectorDurationMs,0));
        if (changed == 0) throw new BizException(409,"文档正在处理中，请等待完成后再试");
        indexVersion.changed(doc.getUserId());
    }

    public KbCollection createCollection(String name, String description) {
        String userId = SecurityUtil.getCurrentUserId();
        KbCollection collection = new KbCollection();
        collection.setId(UUID.randomUUID().toString().replace("-", ""));
        collection.setUserId(userId);
        collection.setName(name);
        collection.setDescription(description);
        collectionMapper.insert(collection);
        indexVersion.changed(userId);
        return collection;
    }

    public List<KbCollection> listCollections(String userId) {
        var collections = collectionMapper.selectList(new LambdaQueryWrapper<KbCollection>().eq(KbCollection::getUserId, userId).orderByDesc(KbCollection::getCreatedAt));
        for (var collection : collections) collection.setDocumentCount(Math.toIntExact(documentMapper.selectCount(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getCollectionId, collection.getId()).eq(KbDocument::getUserId, userId))));
        return collections;
    }

    public Page<KbCollection> pageCollections(String userId, long current, long size) {
        return collectionMapper.selectPage(new Page<>(current, size), new LambdaQueryWrapper<KbCollection>()
                .eq(KbCollection::getUserId, userId)
                .orderByDesc(KbCollection::getCreatedAt));
    }

    public KbCollection getCollection(String id) {
        String userId = SecurityUtil.getCurrentUserId();
        KbCollection c = collectionMapper.selectById(id);
        if (c == null || !userId.equals(c.getUserId())) {
            throw BizException.notFound("知识库不存在");
        }
        return c;
    }

    public void updateCollection(KbCollection collection) {
        String userId = SecurityUtil.getCurrentUserId();
        KbCollection existing = collectionMapper.selectById(collection.getId());
        if (existing == null || !userId.equals(existing.getUserId())) {
            throw new BizException("Collection not found");
        }
        collection.setUserId(null); // prevent overwriting owner
        collectionMapper.updateById(collection);
        indexVersion.changed(userId);
    }

    public void deleteCollection(String id) {
        String userId = SecurityUtil.getCurrentUserId();
        KbCollection c = collectionMapper.selectById(id);
        if (c == null || !userId.equals(c.getUserId())) {
            throw new BizException("Collection not found");
        }
        // Cascade: delete all documents in this collection first
        List<KbDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getCollectionId, id));
        for (KbDocument doc : docs) {
            deleteDocumentInternal(doc);
        }
        collectionMapper.deleteById(id);
        indexVersion.changed(userId);
    }

    /** Administrative account removal, scoped by the stored owner rather than request context. */
    public void deleteAllForUser(String userId) {
        List<KbDocument> docs = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getUserId, userId));
        for (KbDocument doc : docs) deleteDocumentInternal(doc);
        collectionMapper.delete(new LambdaQueryWrapper<KbCollection>().eq(KbCollection::getUserId, userId));
    }

    public KbDocument createDocument(KbDocument document) {
        documentMapper.insert(document);
        indexVersion.changed(document.getUserId());
        return document;
    }

    public KbDocument uploadDocument(String collectionId, String filename, String mimeType, long sizeBytes) {
        String userId = SecurityUtil.getCurrentUserId();
        // Verify collection ownership
        KbCollection c = collectionMapper.selectById(collectionId);
        if (c == null || !userId.equals(c.getUserId())) {
            throw new BizException("Collection not found");
        }

        KbDocument document = new KbDocument();
        document.setId(UUID.randomUUID().toString().replace("-", ""));
        document.setCollectionId(collectionId);
        document.setUserId(userId);
        document.setFilename(filename);
        document.setMimeType(mimeType);
        document.setSizeBytes(sizeBytes);
        document.setStatus("PENDING");
        documentMapper.insert(document);
        indexVersion.changed(document.getUserId());
        return document;
    }

    public List<KbDocument> listDocuments(String collectionId, String userId) {
        getCollection(collectionId);
        return documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getCollectionId, collectionId)
                .eq(KbDocument::getUserId, userId)
                .orderByDesc(KbDocument::getCreatedAt));
    }

    public record DocumentProgress(String id, String status, String progressStage,
                                   Integer processedPages, Integer totalPages,
                                   Integer vectorizedCount, Integer chunkCount,
                                   String errorMessage) {}

    public List<DocumentProgress> listDocumentProgress(String collectionId) {
        KbCollection collection = getCollection(collectionId);
        return documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getCollectionId, collectionId)
                        .eq(KbDocument::getUserId, collection.getUserId())
                        .select(KbDocument::getId, KbDocument::getStatus,
                                KbDocument::getProgressStage, KbDocument::getProcessedPages,
                                KbDocument::getTotalPages, KbDocument::getVectorizedCount,
                                KbDocument::getChunkCount, KbDocument::getErrorMessage))
                .stream().map(doc -> new DocumentProgress(doc.getId(), doc.getStatus(),
                        doc.getProgressStage(), doc.getProcessedPages(), doc.getTotalPages(),
                        doc.getVectorizedCount(), doc.getChunkCount(), doc.getErrorMessage()))
                .toList();
    }

    public Page<KbDocument> pageDocuments(String collectionId, String userId, long current, long size) {
        return documentMapper.selectPage(new Page<>(current, size), new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getCollectionId, collectionId)
                .eq(KbDocument::getUserId, userId)
                .orderByDesc(KbDocument::getCreatedAt));
    }

    public KbDocument getDocument(String id) {
        String userId = SecurityUtil.getCurrentUserId();
        KbDocument d = documentMapper.selectById(id);
        if (d == null || !userId.equals(d.getUserId())) {
            throw BizException.notFound("文档不存在");
        }
        return d;
    }

    public void updateDocument(KbDocument document) {
        String userId = SecurityUtil.getCurrentUserId();
        KbDocument existing = documentMapper.selectById(document.getId());
        if (existing == null || !userId.equals(existing.getUserId())) {
            throw new BizException("Document not found");
        }
        document.setUserId(null); // prevent overwriting owner
        documentMapper.updateById(document);
    }

    public void deleteDocument(String id) {
        String userId = SecurityUtil.getCurrentUserId();
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null || !userId.equals(doc.getUserId())) {
            throw new BizException("Document not found");
        }
        deleteDocumentInternal(doc);
    }

    /** Full cascade delete: vectors → chunks → disk files → document row. */
    private void deleteDocumentInternal(KbDocument doc) {
        if (List.of("PROCESSING", "VECTORIZING").contains(doc.getStatus())) {
            throw new BizException(409, "文档正在处理，请完成后再删除");
        }
        // 1. pgvector embeddings
        vectorSearchService.deleteVectorsByDocument(doc.getId());
        // 2. MySQL chunks
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, doc.getId()));
        // Only remove managed copies; bound originals are retained.
        try { storage.deleteCopy(doc); }
        catch (IOException e) { throw new BizException(500, "清理文档副本失败，请重试"); }
        // 4. Document row
        documentMapper.deleteById(doc.getId());
        indexVersion.changed(doc.getUserId());
    }

    public List<KbChunk> searchChunks(String collectionId, String query, int topK) {
        String userId = SecurityUtil.getCurrentUserId();
        getCollection(collectionId);
        validateSearch(query, topK);
        return hybridSearchService.search(query, userId, collectionId, topK);
    }

    public List<KbChunk> searchAll(String query, int topK) {
        String userId = SecurityUtil.getCurrentUserId();
        validateSearch(query, topK);
        return hybridSearchService.searchAll(query, userId, topK);
    }

    private void validateSearch(String query, int topK) {
        if (query == null || query.isBlank() || query.length() > 4000 || topK < 1 || topK > 20) {
            throw new BizException(400, "查询不能为空且不超过 4000 字，结果数量须为 1–20");
        }
    }

    public void saveDocumentFile(String documentId, MultipartFile file) {
        try { Path saved=storage.saveUpload(documentId,file); var doc=documentMapper.selectById(documentId); if(doc!=null){doc.setStoragePath(saved.toString());documentMapper.updateById(doc);} }
        catch(IOException e){markStorageFailure(documentId);throw new BizException(500,"保存上传文档失败，请重新上传");}
    }
    public void saveDocumentContent(String documentId,String content) {
        try { Path saved=storage.saveBytes(documentId,"content.md",content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); var doc=documentMapper.selectById(documentId); if(doc!=null){doc.setStoragePath(saved.toString());documentMapper.updateById(doc);} }
        catch(IOException e){markStorageFailure(documentId);throw new BizException(500,"保存文档内容失败，请重新导入");}
    }
    private void markStorageFailure(String documentId) {
        documentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId).eq(KbDocument::getStatus, "PENDING")
                .set(KbDocument::getStatus, "FAILED")
                .set(KbDocument::getProgressStage, "FAILED")
                .set(KbDocument::getErrorMessage, "文件保存失败，请重新上传或导入"));
    }
    public void setImportedPath(String documentId,String relativePath) {
        var doc=getDocument(documentId);
        documentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId,doc.getId()).eq(KbDocument::getUserId,doc.getUserId())
                .set(KbDocument::getSourceKind,"IMPORT")
                .set(KbDocument::getSourceRelativePath,FolderPathService.validateRelativePath(relativePath)));
    }

    public record MeasuredSearch(List<KbChunk> chunks,RetrievalStats stats){}
    public MeasuredSearch measuredSearch(String collectionId,String query,int topK){
        String user=SecurityUtil.getCurrentUserId();if(collectionId!=null&&!collectionId.isBlank())getCollection(collectionId);validateSearch(query,topK);
        var stats=new RetrievalStats();var chunks=hybridSearchService.searchLegacy(query,user,RetrievalScope.collection(collectionId),topK,stats);
        stats.evidenceChars=chunks.stream().mapToInt(c->c.getContent().length()).sum();return new MeasuredSearch(chunks,stats);
    }

    public KbChunk getChunk(String id) {
        String userId = SecurityUtil.getCurrentUserId();
        KbChunk chunk = chunkMapper.selectById(id);
        if (chunk == null || !userId.equals(chunk.getUserId())) {
            throw BizException.notFound("文档片段不存在");
        }
        return chunk;
    }

    public void deleteChunk(String id) {
        var chunk=getChunk(id);
        vectorSearchService.deleteVectorsByChunk(id);
        chunkMapper.deleteById(id);
        indexVersion.changed(chunk.getUserId());
    }
}
