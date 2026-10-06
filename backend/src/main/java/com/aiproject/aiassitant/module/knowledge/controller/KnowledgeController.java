package com.aiproject.aiassitant.module.knowledge.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.entity.KbCollection;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.service.KnowledgeService;
import com.aiproject.aiassitant.module.knowledge.service.DocumentProcessingService;
import com.aiproject.aiassitant.module.knowledge.service.DocumentExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "Knowledge", description = "Knowledge base collections and documents")
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final com.aiproject.aiassitant.module.knowledge.service.KnowledgeStorageService storage;
    private final DocumentProcessingService documentProcessingService;
    private final DocumentExportService documentExportService;
    private final com.aiproject.aiassitant.module.knowledge.service.SourceSnapshotService sourceSnapshots;

    @Operation(summary = "Create a knowledge collection")
    @PostMapping("/collections")
    public R<KbCollection> createCollection(@RequestBody(required = false) KbCollection req) {
        String name = req != null && req.getName() != null ? req.getName() : "Default Collection";
        String description = req != null ? req.getDescription() : null;
        KbCollection collection = knowledgeService.createCollection(name, description);
        return R.ok(collection);
    }

    @Operation(summary = "List collections for current user")
    @GetMapping("/collections")
    public R<List<KbCollection>> listCollections() {
        String userId = SecurityUtil.getCurrentUserId();
        return R.ok(knowledgeService.listCollections(userId));
    }

    @Operation(summary = "Delete a collection")
    @DeleteMapping("/collections/{id}")
    public R<Void> deleteCollection(@PathVariable String id) {
        knowledgeService.deleteCollection(id);
        return R.ok();
    }

    @Operation(summary = "Upload a document into a collection (max 100MB)")
    @PostMapping("/collections/{collectionId}/documents")
    public R<KbDocument> uploadDocument(@PathVariable String collectionId,
                                        @RequestParam("file") MultipartFile file,
                                        @RequestParam(required = false) String relativePath) {
        if (file.getSize() > 100L * 1024 * 1024) {
            throw new com.aiproject.aiassitant.common.BizException(413, "文件大小不能超过 100MB");
        }
        if (file.isEmpty()) throw new com.aiproject.aiassitant.common.BizException(400, "文件不能为空");
        if(relativePath!=null) com.aiproject.aiassitant.module.knowledge.service.FolderPathService.validateRelativePath(relativePath);
        KbDocument document = knowledgeService.uploadDocument(
                collectionId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize()
        );

        // Save file to storage
        knowledgeService.saveDocumentFile(document.getId(), file);
        if(relativePath!=null) knowledgeService.setImportedPath(document.getId(),relativePath);

        // Async processing
        try {
            documentProcessingService.processDocument(document.getId());
        } catch (org.springframework.core.task.TaskRejectedException e) {
            documentProcessingService.markQueueFailure(document.getId());
            throw new com.aiproject.aiassitant.common.BizException(503, "文档处理队列已满，请稍后重试");
        }

        return R.ok(document);
    }

    @Operation(summary = "List documents in a collection")
    @GetMapping("/collections/{collectionId}/documents")
    public R<List<KbDocument>> listDocuments(@PathVariable String collectionId) {
        String userId = SecurityUtil.getCurrentUserId();
        return R.ok(knowledgeService.listDocuments(collectionId, userId));
    }

    @GetMapping("/collections/{collectionId}/documents/progress")
    public R<List<KnowledgeService.DocumentProgress>> listDocumentProgress(@PathVariable String collectionId) {
        return R.ok(knowledgeService.listDocumentProgress(collectionId));
    }

    @Operation(summary = "Search chunks by text")
    @GetMapping("/search")
    public ResponseEntity<R<List<KbChunk>>> search(@RequestParam String q,@RequestParam(defaultValue="5")int limit,@RequestParam(required=false)String collectionId){
        var result=knowledgeService.measuredSearch(collectionId,q,limit);var stats=result.stats();
        return ResponseEntity.ok().header("X-Retrieval-Ms",Long.toString(stats.elapsedMs))
                .header("X-Embedding-Requests",Integer.toString(stats.embeddingRequests)).header("X-Rerank-Requests",Integer.toString(stats.rerankRequests))
                .header("X-Rerank-Documents",Integer.toString(stats.rerankDocuments)).header("X-Evidence-Chars",Integer.toString(stats.evidenceChars)).body(R.ok(result.chunks()));
    }

    @Operation(summary = "Get document processing status")
    @GetMapping("/documents/{id}/status")
    public R<java.util.Map<String, Object>> getDocumentStatus(@PathVariable String id) {
        KbDocument doc = knowledgeService.getDocument(id);
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("id", doc.getId());
        status.put("status", doc.getStatus());
        status.put("filename", doc.getFilename());
        status.put("chunkCount", doc.getChunkCount() != null ? doc.getChunkCount() : 0);
        status.put("progressStage", doc.getProgressStage());
        status.put("processedPages", doc.getProcessedPages());
        status.put("totalPages", doc.getTotalPages());
        status.put("vectorizedCount", doc.getVectorizedCount());
        status.put("parseDurationMs", doc.getParseDurationMs());
        status.put("chunkDurationMs", doc.getChunkDurationMs());
        status.put("vectorDurationMs", doc.getVectorDurationMs());
        status.put("errorMessage", doc.getErrorMessage());
        status.put("parseReportJson", doc.getParseReportJson());
        status.put("createdAt", doc.getCreatedAt());
        status.put("updatedAt", doc.getUpdatedAt());
        return R.ok(status);
    }

    @PostMapping("/documents/{id}/retry")
    public R<Void> retryDocument(@PathVariable String id) {
        KbDocument doc = knowledgeService.getDocument(id);
        if (!List.of("FAILED", "VECTOR_FAILED").contains(doc.getStatus())) {
            throw new com.aiproject.aiassitant.common.BizException(409, "仅失败的文档可以重试");
        }
        try {
            documentProcessingService.processDocument(id);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            throw new com.aiproject.aiassitant.common.BizException(503, "文档处理队列已满，请稍后重试");
        }
        return R.ok();
    }

    @PostMapping("/documents/{id}/reparse")
    public R<Void> reparseDocument(@PathVariable String id) {
        knowledgeService.queueReparse(id);
        try { documentProcessingService.processDocument(id); }
        catch (org.springframework.core.task.TaskRejectedException e) {
            documentProcessingService.markQueueFailure(id);
            throw new com.aiproject.aiassitant.common.BizException(503,"文档处理队列已满，请稍后重试");
        }
        return R.ok();
    }

    @Operation(summary = "Download/preview document file — for DOCX/TXT/MD returns text, others return raw")
    @GetMapping("/documents/{id}/file")
    public org.springframework.http.ResponseEntity<?> previewDocument(@PathVariable String id) {
        KbDocument doc = knowledgeService.getDocument(id);
        java.io.File file = resolveDocumentFile(doc);
        if (file == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }

        String filename = doc.getFilename() != null ? doc.getFilename().toLowerCase() : "";
        // For text-based documents, return parsed text content instead of raw bytes
        if (filename.endsWith(".docx") || filename.endsWith(".doc")) {
            try {
                String text = extractDocxText(file);
                return org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body(text);
            } catch (Exception e) {
                log.warn("Failed to parse DOCX for preview: {}", e.getMessage());
            }
        }
        if (filename.endsWith(".txt") || filename.endsWith(".md")) {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                return org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body(text);
            } catch (Exception e) {
                log.warn("Failed to read text file: {}", e.getMessage());
            }
        }

        // Default: return raw file
        org.springframework.core.io.FileSystemResource resource = new org.springframework.core.io.FileSystemResource(file);
        String mime = filename.endsWith(".pdf") ? "application/pdf"
                : doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
        ContentDisposition cd = ContentDisposition.inline()
                .filename(doc.getFilename() != null ? doc.getFilename() : "file", StandardCharsets.UTF_8)
                .build();
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(mime))
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(resource);
    }

    private java.io.File resolveDocumentFile(KbDocument doc) {
        try{return storage.previewFile(doc).toFile();}catch(java.io.IOException e){return null;}
    }

    private String extractDocxText(java.io.File file) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             org.apache.poi.xwpf.usermodel.XWPFDocument docx = new org.apache.poi.xwpf.usermodel.XWPFDocument(fis)) {
            StringBuilder sb = new StringBuilder();
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : docx.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            return sb.toString();
        }
    }

    @Operation(summary = "Export a single document in the requested format (md/pdf/docx)")
    @GetMapping("/documents/{id}/export")
    public ResponseEntity<byte[]> exportDocument(@PathVariable String id,
                                                  @RequestParam(defaultValue = "md") String format) {
        try {
            DocumentExportService.ExportResult result = documentExportService.exportDocument(id, format);
            ContentDisposition cd = ContentDisposition.attachment()
                    .filename(result.filename(), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(result.mimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                    .body(result.data());
        } catch (com.aiproject.aiassitant.common.BizException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"message\":\"" + e.getMessage() + "\"}").getBytes());
        } catch (Exception e) {
            log.error("Failed to export document {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Batch export selected documents as a ZIP file")
    @PostMapping("/documents/export/batch")
    public ResponseEntity<byte[]> exportBatch(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> documentIds = (List<String>) body.get("documentIds");
            String format = (String) body.getOrDefault("format", "md");

            DocumentExportService.ExportResult result = documentExportService.exportBatch(documentIds, format);
            ContentDisposition cd = ContentDisposition.attachment()
                    .filename(result.filename(), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(result.mimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                    .body(result.data());
        } catch (com.aiproject.aiassitant.common.BizException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"message\":\"" + e.getMessage() + "\"}").getBytes());
        } catch (Exception e) {
            log.error("Failed to batch export documents", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Delete a document")
    @DeleteMapping("/documents/{documentId}")
    public R<Void> deleteDocument(@PathVariable String documentId) {
        knowledgeService.deleteDocument(documentId);
        return R.ok();
    }

    @Operation(summary = "Get a single chunk by id")
    @GetMapping("/chunks/{id}")
    public R<KbChunk> getChunk(@PathVariable String id) {
        return R.ok(knowledgeService.getChunk(id));
    }

    @GetMapping("/chunks/{id}/source")
    public R<com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser.SourceView> getChunkSource(@PathVariable String id) {
        KbChunk chunk = knowledgeService.getChunk(id);
        KbDocument doc = knowledgeService.getDocument(chunk.getDocumentId());
        return R.ok(com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser.view(
                doc.getId(), doc.getFilename(), chunk.getOrdinal(), sourceSnapshots.load(doc.getId()),
                chunk.getSourceStart(), chunk.getSourceEnd(), chunk.getContent()));
    }
}
